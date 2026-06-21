package io.github.zongkx.ffm;

import java.lang.foreign.*;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

public class DuckDBConnection implements AutoCloseable {
    private final MemorySegment connHandle;
    private final Arena arena = Arena.ofShared();

    // 并发控制锁，确保多线程注册/注销 Statement 时的安全
    private final ReentrantLock connLock = new ReentrantLock();

    // 生命周期追踪集合，用来登记由此连接创建的所有活跃 Statement 适配器/包装对象
    private final LinkedHashSet<AutoCloseable> activeStatements = new LinkedHashSet<>();

    private volatile boolean isClosed = false;

    public DuckDBConnection(DuckDBDatabase db) {
        try {
            MemorySegment outConn = arena.allocate(DuckDBNative.C_POINTER);
            int rc = (int) DuckDBNative.duckdb_connect.HANDLE.invokeExact(db.getHandle(), outConn);
            if (rc != 0) {
                throw new RuntimeException("DuckDB 连接建立失败");
            }

            this.connHandle = outConn.get(DuckDBNative.C_POINTER, 0);
        } catch (Throwable t) {
            arena.close();
            throw new RuntimeException(t);
        }
    }

    public MemorySegment getHandle() {
        return connHandle;
    }

    /**
     * 由第三层的 createStatement 或 prepareStatement 调用，用来登记上层适配器
     */
    public void trackStatement(AutoCloseable stmt) {
        connLock.lock();
        try {
            if (!isClosed) {
                activeStatements.add(stmt);
            }
        } finally {
            connLock.unlock();
        }
    }

    /**
     * 当上层的 Statement 执行 close() 时，会反向回调此方法，
     * 将自己从 Connection 的常驻集合中移除，防止内存泄漏
     */
    public void untrackStatement(AutoCloseable stmt) {
        connLock.lock();
        try {
            activeStatements.remove(stmt);
        } finally {
            connLock.unlock();
        }
    }

    // 快捷执行普通 SQL
    public DuckDBResultSet query(String sql) {
        return new DuckDBResultSet(this, sql);
    }

    /**
     * 级联式安全释放（锁分离优化：防止逆序回调导致分布式死锁）
     */
    @Override
    public void close() {
        List<AutoCloseable> targets;

        connLock.lock();
        try {
            if (isClosed) return;

            // 1. 提取快照并立即清空容器，阻断后续追溯
            targets = new ArrayList<>(activeStatements);
            Collections.reverse(targets);
            activeStatements.clear();

            isClosed = true;
        } finally {
            // 2. 提前释放连接锁！防止下游 stmt.close() 内部触发 untrackStatement 导致死锁
            connLock.unlock();
        }

        // 3. 在锁外安全地逆序强制关闭所有还没来得及 close 的 Statement
        for (AutoCloseable stmt : targets) {
            try {
                stmt.close();
            } catch (Exception ignored) {
                // 抑制单个组件清理异常，确保清理链不中断
            }
        }

        // 4. 断开底层 C 连接并回收本地 Arena
        try {
            if (connHandle != null && !connHandle.equals(MemorySegment.NULL)) {
                DuckDBNative.duckdb_disconnect.HANDLE.invokeExact(connHandle);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            arena.close();
        }
    }
}