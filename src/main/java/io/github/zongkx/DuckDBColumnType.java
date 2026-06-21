package io.github.zongkx;

import java.sql.Types;

public enum DuckDBColumnType {
    BOOLEAN(Types.BOOLEAN),
    TINYINT(Types.TINYINT),
    SMALLINT(Types.SMALLINT),
    INTEGER(Types.INTEGER),
    BIGINT(Types.BIGINT),
    UTINYINT(Types.TINYINT),       // 无符号，暂时映射到有符号类型
    USMALLINT(Types.SMALLINT),
    UINTEGER(Types.INTEGER),
    UBIGINT(Types.BIGINT),
    HUGEINT(Types.NUMERIC),        // 128 位整数，映射为 NUMERIC
    UHUGEINT(Types.NUMERIC),
    FLOAT(Types.FLOAT),
    DOUBLE(Types.DOUBLE),
    DECIMAL(Types.DECIMAL),
    VARCHAR(Types.VARCHAR),
    BLOB(Types.BLOB),
    TIME(Types.TIME),
    TIME_NS(Types.TIME),
    DATE(Types.DATE),
    TIMESTAMP(Types.TIMESTAMP),
    TIMESTAMP_MS(Types.TIMESTAMP),
    TIMESTAMP_NS(Types.TIMESTAMP),
    TIMESTAMP_S(Types.TIMESTAMP),
    TIMESTAMP_WITH_TIME_ZONE(Types.TIMESTAMP_WITH_TIMEZONE),
    BIT(Types.BIT),
    TIME_WITH_TIME_ZONE(Types.TIME_WITH_TIMEZONE),
    INTERVAL(Types.OTHER),
    LIST(Types.ARRAY),
    STRUCT(Types.STRUCT),
    ENUM(Types.VARCHAR),           // DuckDB ENUM 可视为 VARCHAR
    UUID(Types.OTHER),             // JDBC 没有 UUID 类型，暂用 OTHER
    JSON(Types.VARCHAR),
    MAP(Types.JAVA_OBJECT),
    ARRAY(Types.ARRAY),
    UNKNOWN(Types.OTHER),
    UNION(Types.OTHER),
    VARIANT(Types.OTHER),
    GEOMETRY(Types.OTHER);

    private final int jdbcType;

    DuckDBColumnType(int jdbcType) {
        this.jdbcType = jdbcType;
    }

    public int getJdbcType() {
        return jdbcType;
    }
}