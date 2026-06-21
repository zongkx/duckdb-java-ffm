package io.github.zongkx;

import io.github.zongkx.ffm.DuckDBConnection;
import io.github.zongkx.ffm.DuckDBNative; // 确保引入 FFM 绑定层
import io.github.zongkx.ffm.DuckDBResultSet;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;

public class DuckDBJdbcStatement implements java.sql.Statement {

    protected final DuckDBJdbcConnection connection; // 修正类型为 JDBC 包装层
    protected final DuckDBConnection nativeConn;

    private ResultSet currentResultSet; // 修正为 JDBC 接口类型
    private boolean isClosed = false;

    public DuckDBJdbcStatement(DuckDBJdbcConnection connection, DuckDBConnection nativeConn) {
        this.connection = connection;
        this.nativeConn = nativeConn;
    }

    protected void checkOpen() throws SQLException {
        if (isClosed) {
            throw new SQLException("Statement has already been closed.");
        }
    }

    // ==========================================
    // 1. 核心 SQL 执行业务逻辑
    // ==========================================


    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkOpen();
        connection.notifyStatementExecution(); // 核心修复：激活事务状态看门狗
        connection.beginTransactionIfNeeded();
        closeCurrentResultSet();

        try {
            // 将底层结果集封装为 JDBC 规范的 ResultSet
            DuckDBResultSet nativeResult = nativeConn.query(sql);
            this.currentResultSet = new DuckdbJdbcResultSet(nativeResult);
            return this.currentResultSet;
        } catch (Throwable t) {
            throw new SQLException("Failed to execute query: " + sql, t);
        }
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        checkOpen();
        connection.notifyStatementExecution();
        connection.beginTransactionIfNeeded();
        closeCurrentResultSet();

        try (DuckDBResultSet nativeResult = nativeConn.query(sql)) {
            // 通过 DuckDBNative 读取影响行数
            long rows = (long) DuckDBNative.duckdb_rows_changed.HANDLE.invokeExact(nativeResult.getHandle());
            return (int) rows;
        } catch (Throwable t) {
            throw new SQLException("Failed to execute update: " + sql, t);
        }
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkOpen();
        connection.notifyStatementExecution();
        connection.beginTransactionIfNeeded();
        closeCurrentResultSet();

        try {
            // 简单路由逻辑：以 "SELECT" 开头视为查询
            String upperSql = sql.trim().toUpperCase();
            if (upperSql.startsWith("SELECT") || upperSql.startsWith("WITH") || upperSql.startsWith("SHOW")) {
                executeQuery(sql);
                return true;
            } else {
                executeUpdate(sql);
                return false;
            }
        } catch (Throwable t) {
            throw new SQLException("Execute failed: " + sql, t);
        }
    }

    // ==========================================
    // 2. 结果集防漏电清理机制
    // ==========================================

    private void closeCurrentResultSet() {
        if (currentResultSet != null) {
            try {
                currentResultSet.close();
            } catch (Exception ignored) {
            } finally {
                currentResultSet = null;
            }
        }
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return currentResultSet;
    }

    // ==========================================
    // 3. 级联式安全释放
    // ==========================================

    @Override
    public void close() throws SQLException {
        if (isClosed) return;

        try {
            closeCurrentResultSet();
        } finally {
            if (connection != null) {
                connection.untrackStatement(this);
            }
            isClosed = true;
        }
    }

    // ==========================================
    // 4. 其余必要 JDBC 实现
    // ==========================================

    @Override
    public boolean isClosed() throws SQLException {
        return isClosed;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return -1;
    } // 简化版暂不支持多结果集

    @Override
    public boolean getMoreResults() throws SQLException {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    // ==========================================
    // 5. 剩余方法全部返回默认值或抛出特征异常
    // ==========================================
    @Override
    public int getMaxFieldSize() throws SQLException {
        return 0;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
    }

    @Override
    public int getMaxRows() throws SQLException {
        return 0;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return 0;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
    }

    @Override
    public void cancel() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
    }

    @Override
    public int getFetchSize() throws SQLException {
        return 0;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetType() throws SQLException {
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void clearBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return null;
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return execute(sql);
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return false;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
    }

    @Override
    public void closeOnCompletion() throws SQLException {
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return false;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return false;
    }
}