package io.github.zongkx;

import io.github.zongkx.ffm.DuckDBNative;
import io.github.zongkx.ffm.DuckDBPreparedStatement;
import io.github.zongkx.ffm.DuckDBResultSet;

import java.io.InputStream;
import java.io.Reader;
import java.lang.foreign.MemorySegment;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;

public final class DuckDBJdbcPreparedStatement extends DuckDBJdbcStatement implements java.sql.PreparedStatement {

    private final DuckDBPreparedStatement nativeStmt;
    private boolean isClosed = false;

    // 用于支持标准 execute() 链式调用的内部缓存状态
    private ResultSet currentResultSet = null;
    private int currentUpdateCount = -1;

    public DuckDBJdbcPreparedStatement(DuckDBJdbcConnection connection, DuckDBPreparedStatement nativeStmt) {
        super(connection, null); // 传递给父级 Statement
        this.nativeStmt = nativeStmt;
    }

    protected void checkOpen() throws SQLException {
        if (isClosed) throw new SQLException("PreparedStatement has been closed.");
    }

    // ==========================================
    // 1. 执行核心业务逻辑
    // ==========================================

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkOpen();
        connection.notifyStatementExecution(); // 核心修复：取消注释，让查询正确触发连接层的事务看门狗
        try {
            clearCurrentResult();
            // 拿到 C 层跑出来的普通结构体结果
            MemorySegment outResult = nativeStmt.executeRaw();
            // 包装成我们简易的行式面向对象结果集
            DuckDBResultSet nativeResult = new DuckDBResultSet(outResult);
            // 丢给标准的 JDBC ResultSet 适配器输出
            currentResultSet = new DuckdbJdbcResultSet(nativeResult);
            return currentResultSet;
        } catch (Throwable t) {
            throw new SQLException("Execute query failed", t);
        }
    }

    @Override
    public int executeUpdate() throws SQLException {
        checkOpen();
        connection.notifyStatementExecution(); // 触发 Connection 的事务状态看门狗
        try {
            clearCurrentResult();
            MemorySegment outResult = nativeStmt.executeRaw();
            // 获取受影响行数 (duckdb_rows_changed)
            long rowsChanged = (long) DuckDBNative.duckdb_rows_changed.HANDLE.invokeExact(outResult);
            DuckDBNative.duckdb_destroy_result.HANDLE.invokeExact(outResult);
            currentUpdateCount = (int) rowsChanged;
            return currentUpdateCount;
        } catch (Throwable t) {
            throw new SQLException("Execute update failed", t);
        }
    }

    /**
     * 核心修复：支持标准的通用 execute() 路由，支撑第三方框架底层的混合调用
     */
    @Override
    public boolean execute() throws SQLException {
        checkOpen();
        connection.notifyStatementExecution();
        try {
            clearCurrentResult();
            MemorySegment outResult = nativeStmt.executeRaw();

            // 借鉴官方：这里应当判断底层结果集属于数据块输出（Query）还是纯变更计数（Update）
            // 我们通过 C API 提取受影响行数来粗暴拆分，或者根据业务特征路由。
            // 稳妥起见，由于 DuckDB 执行后都会伴随 Result 实体，我们利用其特征包装：
            long rowsChanged = (long) DuckDBNative.duckdb_rows_changed.HANDLE.invokeExact(outResult);

            if (rowsChanged == 0) {
                // 如果没有受影响行数变更，通常判定其为含有元数据的 Select 查询数据集（或空查询）
                DuckDBResultSet nativeResult = new DuckDBResultSet(outResult);
                currentResultSet = new DuckdbJdbcResultSet(nativeResult);
                return true; // 代表当前结果是个 ResultSet
            } else {
                // 属于写变更操纵
                DuckDBNative.duckdb_destroy_result.HANDLE.invokeExact(outResult);
                currentUpdateCount = (int) rowsChanged;
                return false; // 代表当前结果是个受影响计数
            }
        } catch (Throwable t) {
            throw new SQLException("Execute failed", t);
        }
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkOpen();
        return currentUpdateCount;
    }

    private void clearCurrentResult() throws SQLException {
        if (currentResultSet != null) {
            currentResultSet.close();
            currentResultSet = null;
        }
        currentUpdateCount = -1;
    }

    @Override
    public void clearParameters() throws SQLException {
        checkOpen();
        try {
            nativeStmt.clearBindings();
        } catch (Throwable t) {
            throw new SQLException(t);
        }
    }

    /**
     * 核心修复：提供通用的 Object 类型映射路由，彻底打通 MyBatis 等框架的黑盒传参
     */
    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        checkOpen();
        if (x == null) {
            setNull(parameterIndex, Types.NULL);
            return;
        }
        if (x instanceof String) {
            setString(parameterIndex, (String) x);
        } else if (x instanceof Integer) {
            setInt(parameterIndex, (Integer) x);
        } else if (x instanceof Long) {
            setLong(parameterIndex, (Long) x);
        } else if (x instanceof Boolean) {
            setBoolean(parameterIndex, (Boolean) x);
        } else if (x instanceof Short) {
            setShort(parameterIndex, (Short) x);
        } else if (x instanceof Byte) {
            setByte(parameterIndex, (Byte) x);
        } else {
            // 无法精确识别的其它复杂对象类型，安全降级退化到用其 toString() 文本解译传输
            setString(parameterIndex, x.toString());
        }
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setObject(parameterIndex, x); // 极简驱动策略下，直接忽略 targetSqlType 类型提示，交由上层路由
    }


    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        checkOpen();
        nativeStmt.setInt(parameterIndex, x);   // 直接传，不再减1
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        checkOpen();
        try {
            nativeStmt.setLong(parameterIndex, x);   // 直接传 1-based
        } catch (Throwable t) {
            throw new SQLException(t);
        }
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        checkOpen();
        try {
            nativeStmt.setString(parameterIndex, x); // 直接传 1-based
        } catch (Throwable t) {
            throw new SQLException(t);
        }
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        checkOpen();
        try {
            nativeStmt.setNull(parameterIndex);
        } catch (Throwable t) {
            throw new SQLException(t);
        }
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        checkOpen();
        try {
            nativeStmt.setBoolean(parameterIndex, x);
        } catch (Throwable t) {
            throw new SQLException(t);
        }
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        setInt(parameterIndex, x);   // 直接委托，已经是 1-based
    }

    // ==========================================
    // 3. 级联式安全释放
    // ==========================================

    @Override
    public void close() throws SQLException {
        if (isClosed) return;

        try {
            clearCurrentResult();
            nativeStmt.close(); // 彻底关闭 C 层句柄，并依靠 Arena 回收所有绑定的 String 堆外内存
        } finally {
            connection.untrackStatement(this); // 从父级连接的活动名单中移出
            isClosed = true;
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return isClosed;
    }

    @Override
    public void setShort(int pIdx, short x) throws SQLException {
        setInt(pIdx, x);
    }

    @Override
    public void setDouble(int pIdx, double x) throws SQLException {
        checkOpen();
        try {
            nativeStmt.setDouble((long) pIdx, x);
        } catch (Throwable t) {
            throw new SQLException(t);
        }
    }

    @Override
    public void setFloat(int pIdx, float x) throws SQLException {
        setDouble(pIdx, x);
    }

    @Override
    public void setNull(int pIdx, int sType, String tName) throws SQLException {
        setNull(pIdx, sType);
    }

    @Override
    public void setBigDecimal(int parameterIndex, java.math.BigDecimal x) throws SQLException {
        if (x == null) setNull(parameterIndex, Types.NUMERIC);
        else setString(parameterIndex, x.toString());
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setTimestamp(int pIdx, Timestamp x) throws SQLException {
        throw new SQLFeatureNotSupportedException("待以后借鉴官方的时间转换公式");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setTime(int pIdx, Time x) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setDate(int pIdx, Date x) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void addBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return null;
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        setString(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public final ResultSetMetaData getMetaData() throws SQLException {
        return null;
    }
}