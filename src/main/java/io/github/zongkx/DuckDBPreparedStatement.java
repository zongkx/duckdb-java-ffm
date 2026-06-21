package io.github.zongkx;

import io.github.zongkx.ffm.DuckDBConnection;
import io.github.zongkx.ffm.DuckDBNative;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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

    private void checkIndex(long paramIdx) throws SQLException {
        if (paramIdx < 1) {
            throw new SQLException("Parameter index must be >= 1, got " + paramIdx);
        }
    }

    public void setInt(long paramIdx, int value) throws SQLException {
        checkIndex(paramIdx);
        try {
            int rc = (int) DuckDBNative.duckdb_bind_int32.HANDLE
                    .invokeExact(getRawStmt(), paramIdx, value);
        } catch (Throwable e) {
            throw new SQLException(e);
        }
    }


    public void setString(long paramIdx, String value) throws SQLException {
        if (value == null) {
            try {
                int rc = (int) DuckDBNative.duckdb_bind_null.HANDLE.invokeExact(getRawStmt(), paramIdx);
                check(rc);
            } catch (Throwable e) {
                throw new SQLException(e);
            }
        } else {
            try {
                MemorySegment cStr = arena.allocateFrom(value);
                int rc = (int) DuckDBNative.duckdb_bind_varchar.HANDLE.invokeExact(getRawStmt(), paramIdx, cStr);
                check(rc);
            } catch (Throwable e) {
                throw new SQLException(e);
            }
        }
    }

    public void setDouble(long paramIdx, double value) throws SQLException {
        try {
            int rc = (int) DuckDBNative.duckdb_bind_double.HANDLE.invokeExact(getRawStmt(), paramIdx, value);
            check(rc);
        } catch (Throwable e) {
            throw new SQLException(e);
        }
    }

    public void setLong(long paramIdx, long value) throws SQLException {
        try {
            int rc = (int) DuckDBNative.duckdb_bind_int64.HANDLE.invokeExact(getRawStmt(), paramIdx, value);
            check(rc);
        } catch (Throwable e) {
            throw new SQLException(e);
        }
    }

    public void setBoolean(long paramIdx, boolean value) throws SQLException {
        try {
            byte b = (byte) (value ? 1 : 0);  // DuckDB 的 bool 映射为 1 字节
            int rc = (int) DuckDBNative.duckdb_bind_boolean.HANDLE.invokeExact(getRawStmt(), paramIdx, b);
            check(rc);
        } catch (Throwable e) {
            throw new SQLException(e);
        }
    }


    private void check(int rc) throws SQLException {
        if (rc != 0) {
            throw new SQLException("DuckDB error, rc=" + rc);
        }
    }

    public void setNull(long paramIdx) throws SQLException {
        try {
            int rc = (int) DuckDBNative.duckdb_bind_null.HANDLE.invokeExact(getRawStmt(), paramIdx);
            check(rc);
        } catch (Throwable e) {
            throw new SQLException(e);
        }
    }

    public void clearBindings() throws Throwable {
        DuckDBNative.duckdb_clear_bindings.HANDLE.invokeExact(getRawStmt());
    }


    public MemorySegment executeRaw() throws Throwable {
        MemorySegment outResult = arena.allocate(128);
        int rc = (int) DuckDBNative.duckdb_execute_prepared.HANDLE.invokeExact(getRawStmt(), outResult);
        if (rc != 0) {
            String errorMsg = null;
            MemorySegment errorPtr = (MemorySegment) DuckDBNative.duckdb_result_error.HANDLE.invokeExact(outResult);
            if (errorPtr != null && errorPtr.address() != 0) {
                // 重新解释为可读长度（错误消息一般很短）
                MemorySegment readable = errorPtr.reinterpret(256);
                errorMsg = readable.getString(0);
            }
            DuckDBNative.duckdb_destroy_result.HANDLE.invokeExact(outResult);
            if (errorMsg != null) {
                throw new SQLException("预编译 SQL 执行失败: " + errorMsg);
            } else {
                throw new SQLException("预编译 SQL 执行失败, rc=" + rc);
            }
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