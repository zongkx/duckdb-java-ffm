package io.github.zongkx.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class DuckDBConnection implements AutoCloseable {

    private final MemorySegment connPtr;

    private final MemorySegment connHandle;

    private final Arena arena = Arena.ofShared();

    private final ReentrantLock connLock = new ReentrantLock();

    private final LinkedHashSet<AutoCloseable> activeStatements = new LinkedHashSet<>();

    private volatile boolean isClosed = false;

    public DuckDBConnection(DuckDBDatabase db) {
        try {
            this.connPtr = arena.allocate(DuckDBNative.C_POINTER);
            int rc = (int) DuckDBNative.duckdb_connect.HANDLE.invokeExact(db.getHandle(), connPtr);
            if (rc != 0) {
                throw new RuntimeException("DuckDB 连接建立失败，rc=" + rc);
            }
            this.connHandle = connPtr.get(DuckDBNative.C_POINTER, 0);
        } catch (Throwable t) {
            try {
                arena.close();
            } catch (Exception ignore) {
            }
            throw new RuntimeException(t);
        }
    }

    public MemorySegment getHandle() {
        if (isClosed) {
            throw new IllegalStateException("Connection already closed");
        }
        return connHandle;
    }

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

    public void untrackStatement(AutoCloseable stmt) {
        connLock.lock();
        try {
            activeStatements.remove(stmt);
        } finally {
            connLock.unlock();
        }

    }

    public DuckDBResultSet query(String sql) {
        return new DuckDBResultSet(this, sql);
    }

    @Override
    public void close() {
        List<AutoCloseable> targets;
        connLock.lock();
        try {
            if (isClosed) {
                return;
            }
            targets = new ArrayList<>(activeStatements);
            Collections.reverse(targets);
            activeStatements.clear();
            isClosed = true;
        } finally {
            connLock.unlock();
        }

        for (AutoCloseable stmt : targets) {
            try {
                stmt.close();
            } catch (Exception ignore) {
            }
        }
        try {
            if (connPtr != null && !connPtr.equals(MemorySegment.NULL)) {
                DuckDBNative.duckdb_disconnect.HANDLE
                        .invokeExact(connPtr);
            }

        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            arena.close();
        }
    }

}