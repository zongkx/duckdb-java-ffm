package io.github.zongkx;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

public class DuckDBResultSetMetaData implements ResultSetMetaData {

    private final int columnCount;
    private final String[] columnNames;
    private final String[] columnTypeNames;      // 保持 DuckDB 原始名字输入
    private final DuckDBColumnType[] columnTypes; // 新增：内部解析后的枚举数组

    /**
     * 构造一个结果集元数据对象。由 FFM 读取数据流后调用
     */
    public DuckDBResultSetMetaData(String[] columnNames, String[] columnTypeNames) {
        if (columnNames == null || columnTypeNames == null) {
            throw new IllegalArgumentException("列名和类型数组不能为 null");
        }
        if (columnNames.length != columnTypeNames.length) {
            throw new IllegalArgumentException("列名数组与类型数组长度不一致");
        }
        this.columnCount = columnNames.length;
        this.columnNames = columnNames.clone();
        this.columnTypeNames = columnTypeNames.clone();

        // 一次循环，直接解析为高度复用的枚举
        this.columnTypes = new DuckDBColumnType[columnCount];
        for (int i = 0; i < columnCount; i++) {
            this.columnTypes[i] = DuckDBColumnType.fromTypeName(this.columnTypeNames[i]);
        }
    }

    // ======================== ResultSetMetaData 接口实现 ========================

    @Override
    public int getColumnCount() throws SQLException {
        return columnCount;
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        checkColumnIndex(column);
        return columnNames[column - 1];
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return getColumnName(column);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        checkColumnIndex(column);
        // 极速返回，无需再次进行逻辑判断
        return columnTypes[column - 1].getJdbcType();
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        checkColumnIndex(column);
        return columnTypeNames[column - 1];
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        checkColumnIndex(column);
        // 极速返回，脱离冗长的 switch 匹配
        return columnTypes[column - 1].getJavaClassName();
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        return true;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        return false;
    }

    @Override
    public int isNullable(int column) throws SQLException {
        return columnNullable;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        checkColumnIndex(column);
        DuckDBColumnType type = columnTypes[column - 1];
        // 基于枚举分类，直观安全
        switch (type) {
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case HUGEINT:
            case FLOAT:
            case REAL:
            case DOUBLE:
            case DECIMAL:
            case NUMERIC:
                return true;
            default:
                return false;
        }
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return getPrecision(column);
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        checkColumnIndex(column);
        DuckDBColumnType type = columnTypes[column - 1];
        switch (type) {
            case BOOLEAN:
                return 5;
            case TINYINT:
                return 3;
            case SMALLINT:
                return 5;
            case INTEGER:
                return 10;
            case BIGINT:
                return 19;
            case HUGEINT:
            case UHUGEINT:
                return 38;
            case FLOAT:
            case REAL:
                return 8;
            case DOUBLE:
                return 17;
            case DECIMAL:
            case NUMERIC:
                return 38;
            case DATE:
                return 10;
            case TIME:
                return 8;
            case TIMESTAMP:
                return 29;
            case VARCHAR:
                return Integer.MAX_VALUE;
            default:
                return 0;
        }
    }

    @Override
    public int getScale(int column) throws SQLException {
        checkColumnIndex(column);
        return 0;
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        return "";
    }

    @Override
    public String getTableName(int column) throws SQLException {
        return "";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        return "";
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    private void checkColumnIndex(int column) throws SQLException {
        if (column < 1 || column > columnCount) {
            throw new SQLException("Column index out of range: " + column + " (number of columns: " + columnCount + ")");
        }
    }
}