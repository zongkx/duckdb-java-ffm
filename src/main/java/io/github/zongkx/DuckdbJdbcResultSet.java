package io.github.zongkx;

import io.github.zongkx.ffm.DuckDBResultSet;

import java.net.URL;
import java.sql.*;
import java.math.BigDecimal;
import java.io.InputStream;
import java.io.Reader;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

public class DuckdbJdbcResultSet implements java.sql.ResultSet {

    private final DuckDBResultSet nativeResult;
    private boolean isClosed = false;

    // 核心：用于追踪上一次读取的列是否为 NULL，以支撑 JDBC 规范的 wasNull() 方法
    private boolean lastReadWasNull = false;

    // 惰性列名索引缓存，防止每次调用 findColumn 都在堆外执行 O(N) 字符串重解释比对
    private Map<String, Integer> columnLabelMap = null;

    public DuckdbJdbcResultSet(DuckDBResultSet nativeResult) {
        this.nativeResult = nativeResult;
    }

    private void checkOpen() throws SQLException {
        if (isClosed) throw new SQLException("ResultSet has already been closed.");
    }

    // ==========================================
    // 1. 游标控制与状态看板
    // ==========================================

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        try {
            return nativeResult.next(); // 驱动游标向下滚动一行
        } catch (Throwable t) {
            throw new SQLException("Error moving cursor to next row", t);
        }
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return lastReadWasNull;
    }

    // ==========================================
    // 2. 补齐核心八大数据类型读取 (1-Based -> 0-Based)
    // ==========================================

    @Override
    public String getString(int columnIndex) throws SQLException {
        checkOpen();
        try {
            String val = nativeResult.getString(columnIndex - 1);
            lastReadWasNull = (val == null);
            return val;
        } catch (Throwable t) {
            throw new SQLException("Failed to get String at column " + columnIndex, t);
        }
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        checkOpen();
        try {
            String val = getString(columnIndex);
            if (val == null) {
                lastReadWasNull = true;
                return 0; // JDBC 规范：底层为 NULL 时基本类型返回 0
            }
            lastReadWasNull = false;
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Column " + columnIndex + " value '" + nativeResult.getString(columnIndex - 1) + "' is not a valid int", e);
        }
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        checkOpen();
        try {
            String val = getString(columnIndex);
            if (val == null) {
                lastReadWasNull = true;
                return 0L;
            }
            lastReadWasNull = false;
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Column " + columnIndex + " value is not a valid long", e);
        }
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        checkOpen();
        try {
            String val = getString(columnIndex);
            if (val == null) {
                lastReadWasNull = true;
                return 0.0;
            }
            lastReadWasNull = false;
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Column " + columnIndex + " value is not a valid double", e);
        }
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        checkOpen();
        String val = getString(columnIndex);
        if (val == null) {
            lastReadWasNull = true;
            return false;
        }
        lastReadWasNull = false;
        String trimmed = val.trim();
        return "true".equalsIgnoreCase(trimmed) || "1".equals(trimmed) || "yes".equalsIgnoreCase(trimmed);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        checkOpen();
        try {
            String val = getString(columnIndex);
            if (val == null) {
                lastReadWasNull = true;
                return 0;
            }
            lastReadWasNull = false;
            return Short.parseShort(val.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Column " + columnIndex + " value is not a valid short", e);
        }
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        checkOpen();
        try {
            String val = getString(columnIndex);
            if (val == null) {
                lastReadWasNull = true;
                return 0.0f;
            }
            lastReadWasNull = false;
            return Float.parseFloat(val.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Column " + columnIndex + " value is not a valid float", e);
        }
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        checkOpen();
        try {
            String val = getString(columnIndex);
            if (val == null) {
                lastReadWasNull = true;
                return 0;
            }
            lastReadWasNull = false;
            return Byte.parseByte(val.trim());
        } catch (NumberFormatException e) {
            throw new SQLException("Column " + columnIndex + " value is not a valid byte", e);
        }
    }

    /**
     * 核心修复：更高级的智能类型抹平，防止上层 ORM 报 ClassCastException
     */
    @Override
    public Object getObject(int columnIndex) throws SQLException {
        String val = getString(columnIndex);
        if (val == null) {
            return null; // 保持 lastReadWasNull 的正确设定
        }

        String str = val.trim();
        // 1. 尝试转换为整数
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException ignored) {}

        // 2. 尝试转换为长整数
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException ignored) {}

        // 3. 尝试转换为浮点数
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException ignored) {}

        // 4. 尝试转换为布尔
        if ("true".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str)) {
            return Boolean.parseBoolean(str);
        }

        // 5. 无法识别的标量，安全降级回原始文本返回
        return val;
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    // ==========================================
    // 3. 核心修复：支持通过【列名】查找映射（对齐 MyBatis 体系规范）
    // ==========================================

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkOpen();
        if (columnLabel == null) {
            throw new SQLException("Column label cannot be null");
        }

        // 惰性双重初始化缓存映射
        if (columnLabelMap == null) {
            columnLabelMap = new HashMap<>();
            long totalCols = nativeResult.getTotalColumns();
            for (long i = 0; i < totalCols; i++) {
                try {
                    String name = nativeResult.getColumnName(i);
                    if (name != null) {
                        // 统一存储小写，以便支持不区分大小写查询
                        columnLabelMap.putIfAbsent(name.toLowerCase(), (int) (i + 1));
                    }
                } catch (Throwable t) {
                    throw new SQLException("Failed to resolve metadata columns name from native FFM layer", t);
                }
            }
        }

        Integer idx = columnLabelMap.get(columnLabel.toLowerCase());
        if (idx == null) {
            throw new SQLException("The column label '" + columnLabel + "' was not found in this ResultSet.");
        }
        return idx;
    }

    @Override public String getString(String cLabel) throws SQLException { return getString(findColumn(cLabel)); }
    @Override public int getInt(String cLabel) throws SQLException { return getInt(findColumn(cLabel)); }
    @Override public long getLong(String cLabel) throws SQLException { return getLong(findColumn(cLabel)); }
    @Override public float getFloat(String cLabel) throws SQLException { return getFloat(findColumn(cLabel)); }
    @Override public double getDouble(String cLabel) throws SQLException { return getDouble(findColumn(cLabel)); }
    @Override public boolean getBoolean(String cLabel) throws SQLException { return getBoolean(findColumn(cLabel)); }
    @Override public byte getByte(String cLabel) throws SQLException { return getByte(findColumn(cLabel)); }
    @Override public short getShort(String cLabel) throws SQLException { return getShort(findColumn(cLabel)); }

    // ==========================================
    // 4. 生命周期管理与支持
    // ==========================================

    @Override
    public void close() throws SQLException {
        if (!isClosed) {
            if (columnLabelMap != null) {
                columnLabelMap.clear();
            }
            nativeResult.close(); // 彻底销毁 C 层的结果集结构体内存，杜绝内存泄漏
            isClosed = true;
        }
    }

    @Override
    public boolean isClosed() throws SQLException {
        return isClosed;
    }

    // ==========================================
    // 5. 几百个高级特性全部优雅宣称不受支持
    // ==========================================

    @Override public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public BigDecimal getBigDecimal(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public BigDecimal getBigDecimal(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public byte[] getBytes(String columnLabel) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public byte[] getBytes(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException(); }

    @Override public ResultSetMetaData getMetaData() throws SQLException { return null; }
    @Override public int getType() throws SQLException { return ResultSet.TYPE_FORWARD_ONLY; }
    @Override public int getConcurrency() throws SQLException { return ResultSet.CONCUR_READ_ONLY; }

    @Override public boolean rowUpdated() throws SQLException { return false; }
    @Override public boolean rowInserted() throws SQLException { return false; }
    @Override public boolean rowDeleted() throws SQLException { return false; }

    @Override public int getFetchDirection() throws SQLException { return FETCH_FORWARD; }
    @Override public void setFetchDirection(int direction) throws SQLException {}
    @Override public int getFetchSize() throws SQLException { return 0; }
    @Override public void setFetchSize(int rows) throws SQLException {}

    @Override public Date getDate(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Time getTime(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Timestamp getTimestamp(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Date getDate(String cLabel) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Time getTime(String cLabel) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Timestamp getTimestamp(String cLabel) throws SQLException { throw new SQLFeatureNotSupportedException(); }

    @Override public InputStream getAsciiStream(String columnLabel) throws SQLException { return null; }
    @Override public InputStream getUnicodeStream(String columnLabel) throws SQLException { return null; }
    @Override public InputStream getBinaryStream(String columnLabel) throws SQLException { return null; }
    @Override public InputStream getAsciiStream(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public InputStream getUnicodeStream(int columnIndex) throws SQLException { return null; }
    @Override public InputStream getBinaryStream(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Reader getCharacterStream(int columnIndex) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public Reader getCharacterStream(String columnLabel) throws SQLException { return null; }

    @Override public SQLWarning getWarnings() throws SQLException { return null; }
    @Override public void clearWarnings() throws SQLException {}
    @Override public String getCursorName() throws SQLException { return ""; }

    @Override public void updateRow() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void insertRow() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void deleteRow() throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateString(int cIdx, String x) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public void updateInt(int cIdx, int x) throws SQLException { throw new SQLFeatureNotSupportedException(); }

    @Override public void refreshRow() throws SQLException {}
    @Override public void cancelRowUpdates() throws SQLException {}
    @Override public void moveToInsertRow() throws SQLException {}
    @Override public void moveToCurrentRow() throws SQLException {}
    @Override public Statement getStatement() throws SQLException { return null; }

    @Override public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException { return null; }
    @Override public Ref getRef(int columnIndex) throws SQLException { return null; }
    @Override public Blob getBlob(int columnIndex) throws SQLException { return null; }
    @Override public Clob getClob(int columnIndex) throws SQLException { return null; }
    @Override public Array getArray(int columnIndex) throws SQLException { return null; }
    @Override public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException { return null; }
    @Override public Ref getRef(String columnLabel) throws SQLException { return null; }
    @Override public Blob getBlob(String columnLabel) throws SQLException { return null; }
    @Override public Clob getClob(String columnLabel) throws SQLException { return null; }
    @Override public Array getArray(String columnLabel) throws SQLException { return null; }

    @Override public Date getDate(int columnIndex, Calendar cal) throws SQLException { return null; }
    @Override public Date getDate(String columnLabel, Calendar cal) throws SQLException { return null; }
    @Override public Time getTime(int columnIndex, Calendar cal) throws SQLException { return null; }
    @Override public Time getTime(String columnLabel, Calendar cal) throws SQLException { return null; }
    @Override public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException { return null; }
    @Override public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException { return null; }

    @Override public URL getURL(int columnIndex) throws SQLException { return null; }
    @Override public URL getURL(String columnLabel) throws SQLException { return null; }

    @Override public <T> T getObject(int columnIndex, Class<T> type) throws SQLException { return null; }
    @Override public <T> T getObject(String columnLabel, Class<T> type) throws SQLException { return null; }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }

    // 所有不必须的未实现方法保持默认或空，不阻碍基本操作
    @Override public void updateNull(int columnIndex) throws SQLException {}
    @Override public void updateBoolean(int columnIndex, boolean x) throws SQLException {}
    @Override public void updateByte(int columnIndex, byte x) throws SQLException {}
    @Override public void updateShort(int columnIndex, short x) throws SQLException {}
    @Override public void updateLong(int columnIndex, long x) throws SQLException {}
    @Override public void updateFloat(int columnIndex, float x) throws SQLException {}
    @Override public void updateDouble(int columnIndex, double x) throws SQLException {}
    @Override public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {}
    @Override public void updateBytes(int columnIndex, byte[] x) throws SQLException {}
    @Override public void updateDate(int columnIndex, Date x) throws SQLException {}
    @Override public void updateTime(int columnIndex, Time x) throws SQLException {}
    @Override public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {}
    @Override public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {}
    @Override public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {}
    @Override public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {}
    @Override public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {}
    @Override public void updateObject(int columnIndex, Object x) throws SQLException {}
    @Override public void updateNull(String columnLabel) throws SQLException {}
    @Override public void updateBoolean(String columnLabel, boolean x) throws SQLException {}
    @Override public void updateByte(String columnLabel, byte x) throws SQLException {}
    @Override public void updateShort(String columnLabel, short x) throws SQLException {}
    @Override public void updateInt(String columnLabel, int x) throws SQLException {}
    @Override public void updateLong(String columnLabel, long x) throws SQLException {}
    @Override public void updateFloat(String columnLabel, float x) throws SQLException {}
    @Override public void updateDouble(String columnLabel, double x) throws SQLException {}
    @Override public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {}
    @Override public void updateString(String columnLabel, String x) throws SQLException {}
    @Override public void updateBytes(String columnLabel, byte[] x) throws SQLException {}
    @Override public void updateDate(String columnLabel, Date x) throws SQLException {}
    @Override public void updateTime(String columnLabel, Time x) throws SQLException {}
    @Override public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {}
    @Override public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {}
    @Override public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {}
    @Override public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {}
    @Override public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {}
    @Override public void updateObject(String columnLabel, Object x) throws SQLException {}
    @Override public void updateRef(int columnIndex, Ref x) throws SQLException {}
    @Override public void updateRef(String columnLabel, Ref x) throws SQLException {}
    @Override public void updateBlob(int columnIndex, Blob x) throws SQLException {}
    @Override public void updateBlob(String columnLabel, Blob x) throws SQLException {}
    @Override public void updateClob(int columnIndex, Clob x) throws SQLException {}
    @Override public void updateClob(String columnLabel, Clob x) throws SQLException {}
    @Override public void updateArray(int columnIndex, Array x) throws SQLException {}
    @Override public void updateArray(String columnLabel, Array x) throws SQLException {}
    @Override public RowId getRowId(int columnIndex) throws SQLException { return null; }
    @Override public RowId getRowId(String columnLabel) throws SQLException { return null; }
    @Override public void updateRowId(int columnIndex, RowId x) throws SQLException {}
    @Override public void updateRowId(String columnLabel, RowId x) throws SQLException {}
    @Override public int getHoldability() throws SQLException { return 0; }
    @Override public boolean isBeforeFirst() throws SQLException { return false; }
    @Override public boolean isAfterLast() throws SQLException { return false; }
    @Override public boolean isFirst() throws SQLException { return false; }
    @Override public boolean isLast() throws SQLException { return false; }
    @Override public void beforeFirst() throws SQLException {}
    @Override public void afterLast() throws SQLException {}
    @Override public boolean first() throws SQLException { return false; }
    @Override public boolean last() throws SQLException { return false; }
    @Override public int getRow() throws SQLException { return 0; }
    @Override public boolean absolute(int row) throws SQLException { return false; }
    @Override public boolean relative(int rows) throws SQLException { return false; }
    @Override public boolean previous() throws SQLException { return false; }
    @Override public void updateNString(int columnIndex, String nString) throws SQLException {}
    @Override public void updateNString(String columnLabel, String nString) throws SQLException {}
    @Override public void updateNClob(int columnIndex, NClob nClob) throws SQLException {}
    @Override public void updateNClob(String columnLabel, NClob nClob) throws SQLException {}
    @Override public NClob getNClob(int columnIndex) throws SQLException { return null; }
    @Override public NClob getNClob(String columnLabel) throws SQLException { return null; }
    @Override public SQLXML getSQLXML(int columnIndex) throws SQLException { return null; }
    @Override public SQLXML getSQLXML(String columnLabel) throws SQLException { return null; }
    @Override public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {}
    @Override public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {}
    @Override public String getNString(int columnIndex) throws SQLException { return ""; }
    @Override public String getNString(String columnLabel) throws SQLException { return ""; }
    @Override public Reader getNCharacterStream(int columnIndex) throws SQLException { return null; }
    @Override public Reader getNCharacterStream(String columnLabel) throws SQLException { return null; }
    @Override public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {}
    @Override public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {}
    @Override public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {}
    @Override public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {}
    @Override public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {}
    @Override public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {}
    @Override public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {}
    @Override public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {}
    @Override public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {}
    @Override public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {}
    @Override public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {}
    @Override public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {}
    @Override public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {}
    @Override public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {}
    @Override public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {}
    @Override public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {}
    @Override public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {}
    @Override public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {}
    @Override public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {}
    @Override public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {}
    @Override public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {}
    @Override public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {}
    @Override public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {}
    @Override public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {}
    @Override public void updateClob(int columnIndex, Reader reader) throws SQLException {}
    @Override public void updateClob(String columnLabel, Reader reader) throws SQLException {}
    @Override public void updateNClob(int columnIndex, Reader reader) throws SQLException {}
    @Override public void updateNClob(String columnLabel, Reader reader) throws SQLException {}
}