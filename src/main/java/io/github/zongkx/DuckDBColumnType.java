package io.github.zongkx;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.UUID;

public enum DuckDBColumnType {
    BOOLEAN(Types.BOOLEAN, "java.lang.Boolean"),
    TINYINT(Types.TINYINT, "java.lang.Byte"),
    SMALLINT(Types.SMALLINT, "java.lang.Short"),
    INTEGER(Types.INTEGER, "java.lang.Integer"),
    BIGINT(Types.BIGINT, "java.lang.Long"),
    HUGEINT(Types.NUMERIC, BigInteger.class.getName()),
    UHUGEINT(Types.NUMERIC, BigInteger.class.getName()),
    FLOAT(Types.FLOAT, "java.lang.Float"),
    REAL(Types.REAL, "java.lang.Float"),
    DOUBLE(Types.DOUBLE, "java.lang.Double"),
    DECIMAL(Types.DECIMAL, BigDecimal.class.getName()),
    NUMERIC(Types.DECIMAL, BigDecimal.class.getName()),
    VARCHAR(Types.VARCHAR, "java.lang.String"),
    BLOB(Types.BLOB, "[B"),
    DATE(Types.DATE, LocalDate.class.getName()),
    TIME(Types.TIME, LocalTime.class.getName()),
    TIMESTAMP(Types.TIMESTAMP, Timestamp.class.getName()),
    LIST(Types.ARRAY, "java.sql.Array"),
    ARRAY(Types.ARRAY, "java.sql.Array"),
    STRUCT(Types.STRUCT, "java.sql.Struct"),
    MAP(Types.OTHER, LinkedHashMap.class.getName()),
    ENUM(Types.VARCHAR, "java.lang.String"),
    UUID(Types.OTHER, UUID.class.getName()),
    JSON(Types.OTHER, "java.lang.String"),
    INTERVAL(Types.OTHER, "java.lang.Object"),
    UNKNOWN(Types.OTHER, "java.lang.Object");

    private final int jdbcType;
    private final String javaClassName;

    DuckDBColumnType(int jdbcType, String javaClassName) {
        this.jdbcType = jdbcType;
        this.javaClassName = javaClassName;
    }

    public int getJdbcType() {
        return jdbcType;
    }

    public String getJavaClassName() {
        return javaClassName;
    }

    /**
     * 将 C++ 层通过 FFM 传上来的原始类型字符串快速安全的转换为枚举
     */
    public static DuckDBColumnType fromTypeName(String typeName) {
        if (typeName == null) return UNKNOWN;

        String upper = typeName.toUpperCase().trim();

        // 1. 处理复杂嵌套尾缀 (如 VARCHAR[] -> LIST)
        if (upper.endsWith("]")) {
            return upper.endsWith("[]") ? LIST : ARRAY;
        }

        // 2. 去除括号参数 (如 DECIMAL(10,2) -> DECIMAL)
        int paren = upper.indexOf('(');
        if (paren > 0) {
            upper = upper.substring(0, paren);
        }

        // 3. 别名兼容
        if ("INT".equals(upper)) return INTEGER;
        if ("TEXT".equals(upper)) return VARCHAR;
        if ("CHAR".equals(upper)) return VARCHAR;
        if ("BYTEA".equals(upper)) return BLOB;
        if (upper.startsWith("TIME WITH TIME ZONE")) return TIME;
        if (upper.startsWith("TIMESTAMP WITH TIME ZONE")) return TIMESTAMP;

        try {
            return DuckDBColumnType.valueOf(upper);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
