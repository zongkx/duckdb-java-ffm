package io.github.zongkx;

import io.github.zongkx.ffm.DuckDBConnection;
import io.github.zongkx.ffm.DuckDBResultSet; // 确保引入第二层的结果集用于底层的裸查询

import java.lang.foreign.MemorySegment;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

public final class DuckDBJdbcConnection implements java.sql.Connection {

    private final DuckDBConnection nativeConn;
    private final ReentrantLock connLock = new ReentrantLock();
    private final LinkedHashSet<DuckDBJdbcStatement> activeStatements = new LinkedHashSet<>();
    private volatile boolean autoCommit = true;
    private volatile boolean transactionRunning = false;
    private volatile boolean isClosed = false;

    public DuckDBJdbcConnection(DuckDBConnection nativeConn) {
        this.nativeConn = nativeConn;
    }

    public MemorySegment getNativeHandle() throws SQLException {
        checkOpen();
        return nativeConn.getHandle();
    }

    private void checkOpen() throws SQLException {
        if (isClosed) {
            throw new SQLException("Connection has already been closed.");
        }
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkOpen();
        connLock.lock();
        try {
            checkOpen();
            // 遵循官方驱动：仅支持只读、单向游标流
            if (resultSetConcurrency == ResultSet.CONCUR_READ_ONLY && resultSetType == ResultSet.TYPE_FORWARD_ONLY) {
                DuckDBJdbcStatement stmt = new DuckDBJdbcStatement(this, nativeConn);
                activeStatements.add(stmt);
                return stmt;
            }
            throw new SQLFeatureNotSupportedException("DuckDB only supports TYPE_FORWARD_ONLY and CONCUR_READ_ONLY");
        } finally {
            connLock.unlock();
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return createStatement(resultSetType, resultSetConcurrency, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    public PreparedStatement prepareStatement(String sql, int resultSetType,
                                              int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkOpen();
        connLock.lock();
        try {
            checkOpen();
            if (resultSetConcurrency == ResultSet.CONCUR_READ_ONLY
                    && resultSetType == ResultSet.TYPE_FORWARD_ONLY) {
                DuckDBPreparedStatement nativeStmt = new DuckDBPreparedStatement(nativeConn, sql);
                DuckDBJdbcPreparedStatement pstmt = new DuckDBJdbcPreparedStatement(this, nativeStmt);
                activeStatements.add(pstmt);
                return pstmt;
            }
            throw new SQLFeatureNotSupportedException(
                    "DuckDB PreparedStatement only supports TYPE_FORWARD_ONLY");
        } finally {
            connLock.unlock();
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareStatement(sql, resultSetType, resultSetConcurrency, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    void untrackStatement(DuckDBJdbcStatement stmt) {
        connLock.lock();
        try {
            activeStatements.remove(stmt);
        } finally {
            connLock.unlock();
        }
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();
        connLock.lock();
        try {
            if (this.autoCommit != autoCommit) {
                this.autoCommit = autoCommit;
                // 如果当前处于未提交的事务中，切回 AutoCommit 状态时按照规范隐式提交
                if (transactionRunning && autoCommit) {
                    executeDirectSql("COMMIT;");
                    transactionRunning = false;
                }
            }
        } finally {
            connLock.unlock();
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        return this.autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
        if (autoCommit) {
            throw new SQLException("Cannot commit when autoCommit is enabled.");
        }
        connLock.lock();
        try {
            if (transactionRunning) {
                executeDirectSql("COMMIT;");
                transactionRunning = false;
            }
        } finally {
            connLock.unlock();
        }
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        if (autoCommit) {
            throw new SQLException("Cannot rollback when autoCommit is enabled.");
        }
        connLock.lock();
        try {
            if (transactionRunning) {
                executeDirectSql("ROLLBACK;");
                transactionRunning = false;
            }
            // 如果 transactionRunning 为 false，表示没有事务，无需操作
        } finally {
            connLock.unlock();
        }
    }

    /**
     * 连接内专属工具方法：绕过 JDBC 上层直接与第二层 FFM 交互执行基础事务控制 SQL
     */
    private void executeDirectSql(String sql) throws SQLException {
        try (DuckDBResultSet _ = nativeConn.query(sql)) {
            // 仅仅执行，利用 try-with-resources 自动 close 销毁底层 C 结果集
        } catch (Throwable t) {
            throw new SQLException("Transaction command execution failed: " + sql, t);
        }
    }

    // 在 DuckDBJdbcConnection 中
    void beginTransactionIfNeeded() throws SQLException {
        if (!autoCommit && !transactionRunning) {
            executeDirectSql("BEGIN TRANSACTION;");
            transactionRunning = true;
        }
    }

    void notifyStatementExecution() {

    }

    /**
     * 锁分离设计：杜绝逆序调用子资源的 close() 导致分布式死锁
     */
    @Override
    public void close() throws SQLException {
        if (isClosed) return;

        List<DuckDBJdbcStatement> targets;
        connLock.lock();
        try {
            if (isClosed) return;

            // 1. 锁内仅获取快照并立即清空容器，阻断重入
            targets = new ArrayList<>(activeStatements);
            Collections.reverse(targets);
            activeStatements.clear();

            isClosed = true;
        } finally {
            // 2. 提前释放连接锁！防止下游子资源的释放反向回调 untrackStatement 产生死锁
            connLock.unlock();
        }

        // 3. 在锁外串行、安全地释放下属全部活跃 Statement
        for (DuckDBJdbcStatement stmt : targets) {
            try {
                stmt.close();
            } catch (Exception ignored) {
            }
        }

        // 4. 关闭第二层底层连接
        nativeConn.close();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return isClosed;
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (isClosed()) return false;
        if (timeout <= 0) {   // 违反标准
            throw new SQLException("timeout must be positive");
        }
        // 采用裸查询做心跳检测，防止污染事务状态机
        try (DuckDBResultSet rs = nativeConn.query("SELECT 42;")) {
            return rs.next() && "42".equals(rs.getString(0));
        } catch (Throwable t) {
            return false;
        }
    }

    // ==========================================
    // 标准 JDBC 规范不支持兜底
    // ==========================================
    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return null;
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        if (readOnly) throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return false;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
    }

    @Override
    public String getCatalog() throws SQLException {
        return "main";
    }

    @Override
    public void setSchema(String schema) throws SQLException {
    }

    @Override
    public String getSchema() throws SQLException {
        return "main";
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return Connection.TRANSACTION_REPEATABLE_READ;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        if (level > TRANSACTION_REPEATABLE_READ) throw new SQLFeatureNotSupportedException();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public CallableStatement prepareCall(String sql, int rSType, int rSConcurrency) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public CallableStatement prepareCall(String sql, int rSType, int rSConcurrency, int rSHoldability) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return new HashMap<>();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int getHoldability() throws SQLException {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Clob createClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        throw new SQLClientInfoException();
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        throw new SQLClientInfoException();
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return null;
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return new Properties();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return 0;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }
}