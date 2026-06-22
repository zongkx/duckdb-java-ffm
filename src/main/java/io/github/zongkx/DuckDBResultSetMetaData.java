package io.github.zongkx;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

public class DuckDBResultSetMetaData implements ResultSetMetaData {

    private final int columnCount;
    private final String[] columnNames;
    private final String[] columnTypeNames;   // DuckDB 原始类型名，如 "INTEGER", "VARCHAR"
    private final int[] columnJdbcTypes;      // java.sql.Types 的值

    /**
     * 构造一个结果集元数据对象。
     *
     * @param columnNames     列名数组
     * @param columnTypeNames 列类型名称数组（DuckDB 内部名称）
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
        this.columnJdbcTypes = new int[columnCount];
        for (int i = 0; i < columnCount; i++) {
            this.columnJdbcTypes[i] = duckdbTypeToJdbcType(this.columnTypeNames[i]);
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
        return getColumnName(column); // 标签与名称相同
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        checkColumnIndex(column);
        return columnJdbcTypes[column - 1];
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        checkColumnIndex(column);
        return columnTypeNames[column - 1];
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        int jdbcType = getColumnType(column);
        switch (jdbcType) {
            case Types.BIT:
            case Types.BOOLEAN:
                return "java.lang.Boolean";
            case Types.TINYINT:
                return "java.lang.Byte";
            case Types.SMALLINT:
                return "java.lang.Short";
            case Types.INTEGER:
                return "java.lang.Integer";
            case Types.BIGINT:
                return "java.lang.Long";
            case Types.FLOAT:
            case Types.REAL:
                return "java.lang.Float";
            case Types.DOUBLE:
                return "java.lang.Double";
            case Types.NUMERIC:
            case Types.DECIMAL:
                return "java.math.BigDecimal";
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
                return "java.lang.String";
            case Types.DATE:
                return "java.sql.Date";
            case Types.TIME:
                return "java.sql.Time";
            case Types.TIMESTAMP:
                return "java.sql.Timestamp";
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return "[B"; // byte[]
            case Types.ARRAY:
                return "java.sql.Array";
            case Types.STRUCT:
                return "java.sql.Struct";
            default:
                return "java.lang.Object";
        }
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        return true; // DuckDB 列名默认大小写敏感
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
        return columnNullable; // DuckDB 默认可能为可空，返回 columnNullable 表示未知
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        int type = getColumnType(column);
        return type == Types.TINYINT || type == Types.SMALLINT || type == Types.INTEGER ||
                type == Types.BIGINT || type == Types.FLOAT || type == Types.DOUBLE ||
                type == Types.DECIMAL || type == Types.NUMERIC;
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        // 通常返回 getPrecision 的值
        return getPrecision(column);
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        checkColumnIndex(column);
        int jdbcType = columnJdbcTypes[column - 1];
        switch (jdbcType) {
            case Types.BIT:
            case Types.BOOLEAN:
                return 5;
            case Types.TINYINT:
                return 3;
            case Types.SMALLINT:
                return 5;
            case Types.INTEGER:
                return 10;
            case Types.BIGINT:
                return 19;
            case Types.FLOAT:
                return 8;
            case Types.DOUBLE:
                return 17;
            case Types.DECIMAL:
            case Types.NUMERIC:
                return 38; // 默认精度
            case Types.DATE:
                return 10;
            case Types.TIME:
                return 8;
            case Types.TIMESTAMP:
                return 29;
            case Types.VARCHAR:
            case Types.CHAR:
            case Types.LONGVARCHAR:
                return Integer.MAX_VALUE; // 无限制
            default:
                return 0;
        }
    }

    @Override
    public int getScale(int column) throws SQLException {
        checkColumnIndex(column);
        int jdbcType = columnJdbcTypes[column - 1];
        if (jdbcType == Types.DECIMAL || jdbcType == Types.NUMERIC) {
            return 0; // 未提供具体 scale 时返回 0
        }
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
        return true; // 结果集通常只读
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

    // ======================== 内部工具 ========================

    private void checkColumnIndex(int column) throws SQLException {
        if (column < 1 || column > columnCount) {
            throw new SQLException("Column index out of range: " + column + " (number of columns: " + columnCount + ")");
        }
    }

    /**
     * 将 DuckDB 类型字符串转换为 java.sql.Types 常量。
     * 此映射应与你项目中 DuckDBColumnType 的转换保持一致。
     */
    private static int duckdbTypeToJdbcType(String typeName) {
        if (typeName == null) return Types.OTHER;
        String upper = typeName.toUpperCase();
        // 去除参数部分，如 DECIMAL(10,2) -> DECIMAL
        int paren = upper.indexOf('(');
        if (paren > 0) {
            upper = upper.substring(0, paren);
        }
        switch (upper) {
            case "BOOLEAN":
                return Types.BOOLEAN;
            case "TINYINT":
                return Types.TINYINT;
            case "SMALLINT":
                return Types.SMALLINT;
            case "INTEGER":
            case "INT":
                return Types.INTEGER;
            case "BIGINT":
                return Types.BIGINT;
            case "HUGEINT":
            case "UHUGEINT":
                return Types.NUMERIC;
            case "FLOAT":
                return Types.FLOAT;
            case "REAL":
                return Types.REAL;
            case "DOUBLE":
                return Types.DOUBLE;
            case "DECIMAL":
            case "NUMERIC":
                return Types.DECIMAL;
            case "VARCHAR":
            case "CHAR":
            case "TEXT":
                return Types.VARCHAR;
            case "BLOB":
            case "BYTEA":
                return Types.BLOB;
            case "DATE":
                return Types.DATE;
            case "TIME":
            case "TIME WITH TIME ZONE":
                return Types.TIME;
            case "TIMESTAMP":
            case "TIMESTAMP WITH TIME ZONE":
                return Types.TIMESTAMP;
            case "INTERVAL":
                return Types.OTHER;
            case "UUID":
                return Types.OTHER;
            case "JSON":
                return Types.OTHER;
            case "LIST":
            case "ARRAY":
                return Types.ARRAY;
            case "STRUCT":
                return Types.STRUCT;
            case "MAP":
                return Types.OTHER;
            case "ENUM":
                return Types.VARCHAR;
            default:
                return Types.OTHER;
        }
    }
}