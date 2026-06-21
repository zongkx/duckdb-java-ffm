package io.github.zongkx;

import io.github.zongkx.ffm.DuckDBConnection;
import io.github.zongkx.ffm.DuckDBNative;

import java.lang.foreign.*;
import java.sql.SQLException;

public class DuckDBPreparedStatement implements AutoCloseable {
    private final MemorySegment stmtHandle; // 对应 C 的 duckdb_prepared_statement
    private final Arena arena = Arena.ofConfined(); // 独立闭环的内存域
    private boolean isClosed = false;

    public DuckDBPreparedStatement(DuckDBConnection conn, String sql) throws SQLException {
        try {
            // 分配一个指针大小的内存，用于接收 C 层创建的 statement 句柄
            this.stmtHandle = arena.allocate(DuckDBNative.C_POINTER);
            MemorySegment cSql = arena.allocateFrom(sql);
            
            int rc = (int) DuckDBNative.duckdb_prepare.HANDLE.invokeExact(conn.getHandle(), cSql, stmtHandle);
            if (rc != 0) {
                MemorySegment errorMsg = (MemorySegment) DuckDBNative.duckdb_prepare_error.HANDLE.invokeExact(stmtHandle);
                throw new SQLException("SQL 预编译失败: " + errorMsg.reinterpret(Long.MAX_VALUE).getString(0));
            }
        } catch (Throwable t) {
            arena.close();
            throw new SQLException(t);
        }
    }

    private MemorySegment getRawStmt() {
        return stmtHandle.get(DuckDBNative.C_POINTER, 0);
    }

    // ==========================================
    // 核心参数绑定实现（0-Based 绝对坐标）
    // ==========================================

    public void setInt(long paramIdx, int value) throws Throwable {
        DuckDBNative.duckdb_bind_int32.HANDLE.invokeExact(getRawStmt(), paramIdx, value);
    }

    public void setLong(long paramIdx, long value) throws Throwable {
        DuckDBNative.duckdb_bind_int64.HANDLE.invokeExact(getRawStmt(), paramIdx, value);
    }

    public void setString(long paramIdx, String value) throws Throwable {
        if (value == null) {
            DuckDBNative.duckdb_bind_null.HANDLE.invokeExact(getRawStmt(), paramIdx);
        } else {
            // 在当前 Statement 的 arena 中分配临时字符串，close 时会自动批量销毁
            MemorySegment cStr = arena.allocateFrom(value);
            DuckDBNative.duckdb_bind_varchar.HANDLE.invokeExact(getRawStmt(), paramIdx, cStr);
        }
    }

    public void setNull(long paramIdx) throws Throwable {
        DuckDBNative.duckdb_bind_null.HANDLE.invokeExact(getRawStmt(), paramIdx);
    }

    public void clearBindings() throws Throwable {
        // 对应 C API: void duckdb_clear_bindings(duckdb_prepared_statement prepared_statement)
        // 允许在上层执行完一次后，清空参数重新绑定
        DuckDBNative.duckdb_clear_bindings.HANDLE.invokeExact(getRawStmt());
    }

    // 执行并返回裸结果
    public MemorySegment executeRaw() throws Throwable {
        // 分配 128 字节用于承载 duckdb_result 结构体实体
        MemorySegment outResult = arena.allocate(128); 
        int rc = (int) DuckDBNative.duckdb_execute_prepared.HANDLE.invokeExact(getRawStmt(), outResult);
        if (rc != 0) {
            throw new SQLException("预编译 SQL 执行失败");
        }
        return outResult;
    }

    @Override
    public void close() {
        if (!isClosed) {
            try {
                DuckDBNative.duckdb_destroy_prepare.HANDLE.invokeExact(stmtHandle);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                arena.close(); // 彻底断后，释放所有绑定的临时字符串 C 内存
                isClosed = true;
            }
        }
    }
}