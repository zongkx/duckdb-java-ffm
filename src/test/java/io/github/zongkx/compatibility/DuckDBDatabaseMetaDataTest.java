package io.github.zongkx.compatibility;

import io.github.zongkx.DuckDBDatabaseMetaData;
import io.github.zongkx.DuckDBJdbcConnection;
import io.github.zongkx.ffm.DuckDBConnection;
import io.github.zongkx.ffm.DuckDBDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DuckDBDatabaseMetaDataTest {

    private static DuckDBJdbcConnection connection;
    private static DuckDBDatabaseMetaData metaData;

    @BeforeAll
    static void setUp() throws Exception {
        // 1. 用您的 FFM 库创建内存数据库
        DuckDBDatabase db = new DuckDBDatabase();          // 相当于 duckdb_open(NULL, &db)
        DuckDBConnection nativeConn = db.connect();        // 相当于 duckdb_connect(db, &conn)

        // 2. 包装为 JDBC 连接
        connection = new DuckDBJdbcConnection(nativeConn);
        metaData = new DuckDBDatabaseMetaData(connection);

        // 3. 创建测试表（通过 JDBC 接口，方便后续验证）
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE test_table (id INTEGER, name VARCHAR)");
            stmt.execute("CREATE VIEW test_view AS SELECT 1");
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("getTables 应该返回基本表与视图")
    void testGetTables() throws SQLException {
        // 检索所有表（不限定类型）
        ResultSet rs = metaData.getTables(null, null, "test_table", null);
        assertTrue(rs.next(), "应该至少有一行数据");
        assertEquals("test_table", rs.getString("TABLE_NAME"));
        assertEquals("TABLE", rs.getString("TABLE_TYPE"));
        assertFalse(rs.next(), "只应匹配一行");

        // 检索视图
        rs = metaData.getTables(null, null, "test_view", null);
        assertTrue(rs.next());
        assertEquals("VIEW", rs.getString("TABLE_TYPE"));
    }

    @Test
    @Order(2)
    @DisplayName("getColumns 应该返回列信息")
    void testGetColumns() throws SQLException {
        ResultSet rs = metaData.getColumns(null, null, "test_table", null);
        assertTrue(rs.next(), "第一列");
        assertEquals("id", rs.getString("COLUMN_NAME"));
        // DuckDB 的 data_type 对应 duckdb_columns 中的字段，可能显示 INTEGER
        assertNotNull(rs.getString("TYPE_NAME"));

        assertTrue(rs.next(), "第二列");
        assertEquals("name", rs.getString("COLUMN_NAME"));
        assertFalse(rs.next(), "应该只有两列");
    }

    @Test
    @Order(3)
    @DisplayName("getTables 支持 LIKE 模式匹配")
    void testGetTablesWithPattern() throws SQLException {
        // 使用 LIKE 模式 '%table'
        ResultSet rs = metaData.getTables(null, null, "%table", null);
        assertTrue(rs.next());
        assertEquals("test_table", rs.getString("TABLE_NAME"));
        // 不应该包含 test_view
        assertFalse(rs.next());
    }

    @Test
    @Order(4)
    @DisplayName("getDatabaseProductName 应返回 DuckDB")
    void testProductName() throws SQLException {
        assertEquals("DuckDB", metaData.getDatabaseProductName());
    }

    @Test
    @Order(5)
    @DisplayName("supportsTransactions 应返回 true")
    void testSupportsTransactions() throws SQLException {
        assertTrue(metaData.supportsTransactions());
    }

}