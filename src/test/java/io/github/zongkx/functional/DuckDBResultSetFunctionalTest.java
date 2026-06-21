package io.github.zongkx.functional;

import org.junit.jupiter.api.*;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResultSet 全功能验证测试
 *
 * 覆盖列读取、类型转换、游标控制、边界条件等核心行为。
 */
@Tag("functional")
@DisplayName("ResultSet 功能验证测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DuckDBResultSetFunctionalTest {

    private static Connection conn;

    @BeforeAll
    static void setup() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:memory");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE rs_test ("
                    + "id INTEGER, "
                    + "name VARCHAR, "
                    + "salary DOUBLE, "
                    + "active BOOLEAN, "
                    + "nullable VARCHAR"
                    + ")");
            stmt.execute("INSERT INTO rs_test VALUES "
                    + "(1, 'Alice', 50000.5, true, NULL), "
                    + "(2, 'Bob', 60000.0, false, 'not null'), "
                    + "(3, 'Charlie', 70000.75, true, NULL)");
        }
    }

    @AfterAll
    static void teardown() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @Test
    @DisplayName("[RS-01] 读取所有基本类型列")
    void testReadAllTypes() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM rs_test WHERE id = 1")) {
            assertTrue(rs.next());

            assertEquals(1, rs.getInt("id"));
            assertEquals("Alice", rs.getString("name"));
            assertEquals(50000.5, rs.getDouble("salary"), 0.001);
            assertTrue(rs.getBoolean("active"));
        }
    }

    @Test
    @DisplayName("[RS-02] 通过列索引读取")
    void testReadByColumnIndex() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, salary FROM rs_test WHERE id = 1")) {
            assertTrue(rs.next());

            assertEquals(1, rs.getInt(1));
            assertEquals("Alice", rs.getString(2));
            assertEquals(50000.5, rs.getDouble(3), 0.001);
        }
    }

    @Test
    @DisplayName("[RS-03] nullable 列正确返回 null")
    void testNullableColumn() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, nullable FROM rs_test WHERE id = 1")) {
            assertTrue(rs.next());
            assertNull(rs.getString("nullable"));
            assertTrue(rs.wasNull());
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, nullable FROM rs_test WHERE id = 2")) {
            assertTrue(rs.next());
            assertNotNull(rs.getString("nullable"));
            assertEquals("not null", rs.getString("nullable"));
            assertFalse(rs.wasNull());
        }
    }

    @Test
    @DisplayName("[RS-04] null 基本类型应返回默认值")
    void testPrimitiveNullDefault() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 AS id, NULL AS val")) {
            assertTrue(rs.next());

            assertEquals(0, rs.getInt("val"));
            assertTrue(rs.wasNull());

            assertEquals(0L, rs.getLong("val"));
            assertTrue(rs.wasNull());

            assertEquals(0.0, rs.getDouble("val"), 0.001);
            assertTrue(rs.wasNull());

            assertFalse(rs.getBoolean("val"));
            assertTrue(rs.wasNull());
        }
    }

    @Test
    @DisplayName("[RS-05] getObject 智能类型推导")
    void testGetObject() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 AS int_val, 'hello' AS str_val, 3.14 AS dbl_val")) {
            assertTrue(rs.next());

            Object intObj = rs.getObject("int_val");
            assertInstanceOf(Integer.class, intObj);
            assertEquals(1, intObj);

            Object strObj = rs.getObject("str_val");
            assertInstanceOf(String.class, strObj);
            assertEquals("hello", strObj);

            Object dblObj = rs.getObject("dbl_val");
            assertInstanceOf(Double.class, dblObj);
            assertEquals(3.14, (Double) dblObj, 0.001);
        }
    }

    @Test
    @DisplayName("[RS-06] getObject 对 null 返回 null")
    void testGetObjectNull() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT CAST(NULL AS VARCHAR) AS val")) {
            assertTrue(rs.next());
            assertNull(rs.getObject("val"));
        }
    }

    @Test
    @DisplayName("[RS-07] 遍历所有行")
    void testIterateAllRows() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM rs_test ORDER BY id")) {
            int count = 0;
            while (rs.next()) {
                count++;
                assertTrue(rs.getInt("id") > 0);
                assertNotNull(rs.getString("name"));
            }
            assertEquals(3, count);
        }
    }

    @Test
    @DisplayName("[RS-08] findColumn 不区分大小写")
    void testFindColumnCaseInsensitive() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 42 AS \"AnswerValue\"")) {
            rs.next();

            assertEquals(1, rs.findColumn("answervalue"));
            assertEquals(1, rs.findColumn("ANSWERVALUE"));
            assertEquals(1, rs.findColumn("AnswerValue"));

            assertEquals(42, rs.getInt("answervalue"));
            assertEquals(42, rs.getInt("ANSWERVALUE"));
        }
    }

    @Test
    @DisplayName("[RS-09] 空结果集行为")
    void testEmptyResultSet() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM rs_test WHERE id = -1")) {
            assertFalse(rs.next(), "空结果集不应有数据行");
            assertFalse(rs.next(), "连续调用 next() 应持续返回 false");
        }
    }

    @Test
    @DisplayName("[RS-10] getFetchDirection 返回默认值")
    void testFetchDirection() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertEquals(ResultSet.FETCH_FORWARD, rs.getFetchDirection());
        }
    }

    @Test
    @DisplayName("[RS-11] close() 后 isClosed() 正确")
    void testIsClosed() throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT 1");
        assertFalse(rs.isClosed());

        rs.close();
        assertTrue(rs.isClosed());
        stmt.close();
    }

    @Test
    @DisplayName("[RS-12] close 后读取应抛异常")
    void testReadAfterClose() throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT 1 AS val");
        rs.close();
        assertThrows(SQLException.class, () -> rs.getInt("val"));
        stmt.close();
    }

    @Test
    @DisplayName("[RS-13] getShort / getFloat / getByte 类型转换")
    void testTypeConversions() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 42 AS val")) {
            assertTrue(rs.next());

            assertEquals((short) 42, rs.getShort("val"));
            assertEquals(42.0f, rs.getFloat("val"), 0.001f);
            assertEquals((byte) 42, rs.getByte("val"));
        }
    }

    @Test
    @DisplayName("[RS-14] getLong 读取大整数")
    void testGetLong() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 9876543210 AS big")) {
            assertTrue(rs.next());
            assertEquals(9876543210L, rs.getLong("big"));
        }
    }

    @Test
    @DisplayName("[RS-15] getInt 读取 BigInt 可降级")
    void testIntFromBigInt() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 2147483647 AS max_int")) {
            assertTrue(rs.next());
            assertEquals(2147483647, rs.getInt("max_int"));
        }
    }

    @Test
    @DisplayName("[RS-16] BOOLEAN true/false 多种表示")
    void testBooleanVariants() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT true AS a, false AS b")) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean("a"));
            assertFalse(rs.getBoolean("b"));
        }
    }
}
