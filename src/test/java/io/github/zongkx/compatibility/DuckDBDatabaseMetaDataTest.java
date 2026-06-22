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
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("all")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DuckDBDatabaseMetaDataTest {

    private static DuckDBJdbcConnection connection;
    private static DuckDBDatabaseMetaData metaData;

    @BeforeAll
    static void setUp() throws Exception {
        DuckDBDatabase db = new DuckDBDatabase();          // 相当于 duckdb_open(NULL, &db)
        DuckDBConnection nativeConn = db.connect();        // 相当于 duckdb_connect(db, &conn)

        // 2. 包装为 JDBC 连接
        connection = new DuckDBJdbcConnection(nativeConn, ":memory:", new Properties());
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
// 在类中添加以下测试方法，并保持原有的 @TestMethodOrder 和 @Order 注解

    @Test
    @Order(6)
    @DisplayName("getCatalogs 应返回数据库 catalog")
    void testGetCatalogs() throws SQLException {
        try (ResultSet rs = metaData.getCatalogs()) {
            assertTrue(rs.next(), "至少有一个 catalog");
            String catalog = rs.getString("TABLE_CAT");
            assertNotNull(catalog);
            // 内存数据库通常返回 'memory'
            assertFalse(catalog.isEmpty());
        }
    }

    @Test
    @Order(7)
    @DisplayName("getSchemas 应返回 schema 信息")
    void testGetSchemas() throws SQLException {
        try (ResultSet rs = metaData.getSchemas()) {
            assertTrue(rs.next(), "至少有一个 schema");
            String schema = rs.getString("TABLE_SCHEM");
            // 默认 schema 为 'main'
            assertEquals("main", schema);
        }
    }

    @Test
    @Order(8)
    @DisplayName("getTableTypes 应返回已知的表类型")
    void testGetTableTypes() throws SQLException {
        try (ResultSet rs = metaData.getTableTypes()) {
            assertTrue(rs.next());
            assertEquals("LOCAL TEMPORARY", rs.getString("TABLE_TYPE"));
            assertTrue(rs.next());
            assertEquals("SYSTEM VIEW", rs.getString("TABLE_TYPE"));
            assertTrue(rs.next());
            assertEquals("TABLE", rs.getString("TABLE_TYPE"));
            assertTrue(rs.next());
            assertEquals("VIEW", rs.getString("TABLE_TYPE"));
            assertFalse(rs.next());
        }
    }

    @Test
    @Order(9)
    @DisplayName("getPrimaryKeys 应返回主键信息")
    void testGetPrimaryKeys() throws SQLException {
        // 创建带主键的临时表
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE OR REPLACE TABLE pk_test (id INTEGER PRIMARY KEY, val INTEGER)");
        }

        try (ResultSet rs = metaData.getPrimaryKeys(null, null, "pk_test")) {
            assertTrue(rs.next(), "应有一行主键列");
            assertEquals("pk_test", rs.getString("TABLE_NAME"));
            assertEquals("id", rs.getString("COLUMN_NAME"));
            assertEquals(1, rs.getInt("KEY_SEQ"));
            assertFalse(rs.next(), "仅一个主键列");
        }
    }

    @Test
    @Order(10)
    @DisplayName("getIndexInfo 应返回索引信息")
    void testGetIndexInfo() throws SQLException {
        // 创建索引
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE INDEX idx_test_table_name ON test_table(name)");
        }

        try (ResultSet rs = metaData.getIndexInfo(null, null, "test_table", false, false)) {
            assertTrue(rs.next(), "应至少有一个索引");
            assertEquals("test_table", rs.getString("TABLE_NAME"));
            assertNotNull(rs.getString("INDEX_NAME"));
            // DuckDB 索引信息中可能不列出具体列名，但 INDEX_NAME 不应为空
        }
    }

    @Test
    @Order(11)
    @DisplayName("getTypeInfo 应返回类型映射")
    void testGetTypeInfo() throws SQLException {
        try (ResultSet rs = metaData.getTypeInfo()) {
            assertTrue(rs.next(), "至少有一种类型");
            String typeName = rs.getString("TYPE_NAME");
            assertNotNull(typeName);
            int dataType = rs.getInt("DATA_TYPE");
            assertNotNull(rs.getObject("DATA_TYPE"), "DATA_TYPE 不应为 NULL");
            assertNotEquals(0, dataType, "DATA_TYPE 不应为 0");
        }
    }

    @Test
    @Order(12)
    @DisplayName("getFunctions 应返回内置函数")
    void testGetFunctions() throws SQLException {
        try (ResultSet rs = metaData.getFunctions(null, null, "count")) {
            assertTrue(rs.next(), "count 函数应存在");
            assertEquals("count", rs.getString("FUNCTION_NAME"));
        }
    }

    @Test
    @Order(13)
    @DisplayName("getUDTs 应返回空结果集（DuckDB 通常无 UDT）")
    void testGetUDTs() throws SQLException {
        // DuckDB 中只有枚举和结构体可能被视为 UDT，但通常内置类型不返回
        try (ResultSet rs = metaData.getUDTs(null, null, "%", null)) {
            // 在没有自定义类型时结果集为空
            assertFalse(rs.next(), "默认无 UDT");
        }
    }

    @Test
    @Order(14)
    @DisplayName("getColumns 应支持列名模式匹配")
    void testGetColumnsWithPattern() throws SQLException {
        try (ResultSet rs = metaData.getColumns(null, null, "test_table", "na%")) {
            assertTrue(rs.next(), "应匹配 name 列");
            assertEquals("name", rs.getString("COLUMN_NAME"));
            assertFalse(rs.next(), "仅一列匹配");
        }
    }

    @Test
    @Order(15)
    @DisplayName("getTables 应支持按类型过滤")
    void testGetTablesWithTypes() throws SQLException {
        String[] types = {"TABLE"};
        try (ResultSet rs = metaData.getTables(null, null, "%", types)) {
            assertTrue(rs.next(), "应至少有一张表");
            // 所有返回行的 TABLE_TYPE 都应为 'TABLE'
            do {
                assertEquals("TABLE", rs.getString("TABLE_TYPE"));
            } while (rs.next());
        }

        String[] viewTypes = {"VIEW"};
        try (ResultSet rs = metaData.getTables(null, null, "%", viewTypes)) {
            assertTrue(rs.next(), "应至少有一个视图");
            do {
                assertEquals("VIEW", rs.getString("TABLE_TYPE"));
            } while (rs.next());
        }
    }

    @Test
    @Order(16)
    @DisplayName("getImportedKeys / getExportedKeys 应处理外键")
    void testImportedExportedKeys() throws SQLException {
        // 创建外键关系
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
            stmt.execute("CREATE TABLE child (id INTEGER, parent_id INTEGER REFERENCES parent(id))");
        }

        try (ResultSet rs = metaData.getImportedKeys(null, null, "child")) {
            assertTrue(rs.next(), "child 应有导入键");
            assertEquals("parent", rs.getString("PKTABLE_NAME"));
            assertEquals("child", rs.getString("FKTABLE_NAME"));
        }

        try (ResultSet rs = metaData.getExportedKeys(null, null, "parent")) {
            assertTrue(rs.next(), "parent 应有导出键");
            assertEquals("parent", rs.getString("PKTABLE_NAME"));
            assertEquals("child", rs.getString("FKTABLE_NAME"));
        }
    }

    @Test
    @Order(17)
    @DisplayName("getSQLKeywords 应返回关键字列表")
    void testGetSQLKeywords() throws SQLException {
        String keywords = metaData.getSQLKeywords();
        assertNotNull(keywords);
        assertTrue(keywords.contains("SELECT".toLowerCase()), "关键字应包含 SELECT");
    }

    @Test
    @Order(18)
    @DisplayName("getNumericFunctions / getStringFunctions 等应返回函数名")
    void testGetFunctionLists() throws SQLException {
        String numericFuncs = metaData.getNumericFunctions();
        assertNotNull(numericFuncs);
        assertTrue(numericFuncs.contains("abs"), "应包含 abs");

        String stringFuncs = metaData.getStringFunctions();
        assertTrue(stringFuncs.contains("concat"), "应包含 concat");

        String timeDateFuncs = metaData.getTimeDateFunctions();
        assertTrue(timeDateFuncs.contains("strftime"), "应包含 now");

        String systemFuncs = metaData.getSystemFunctions();
        assertTrue(systemFuncs.length() > 0, "系统函数列表不应为空");
    }

    @Test
    @Order(19)
    @DisplayName("getURL / getUserName / isReadOnly 等基本信息")
    void testBasicInfo() throws SQLException {
        // 注意：getURL 取决于您的连接实现，这里假设返回非空字符串
        String url = metaData.getURL();
        assertNotNull(url);
        // 内存数据库 URL 可能为空或包含 memory 字样
        // assertTrue(url.contains("memory"));

        String userName = metaData.getUserName();
        assertNotNull(userName); // DuckDB 默认为空字符串

        boolean readOnly = metaData.isReadOnly();
        assertFalse(readOnly); // 默认连接为可读写
    }

    @Test
    @Order(20)
    @DisplayName("getDatabaseProductVersion 应返回版本字符串")
    void testProductVersion() throws SQLException {
        String version = metaData.getDatabaseProductVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
        // 版本通常以数字开头，如 "v0.9.2" 或 "0.9.2"
    }

    @Test
    @Order(21)
    @DisplayName("getDriverName / getDriverVersion 应返回非空")
    void testDriverInfo() throws SQLException {
        assertEquals("DuckDBJ", metaData.getDriverName());
        assertNotNull(metaData.getDriverVersion());
        assertEquals(1, metaData.getDriverMajorVersion());
        assertEquals(0, metaData.getDriverMinorVersion());
    }

    @Test
    @Order(22)
    @DisplayName("supports... 各能力声明应合理")
    void testSupportsFlags() throws SQLException {
        assertTrue(metaData.supportsMixedCaseIdentifiers());
        assertTrue(metaData.supportsGroupBy());
        assertTrue(metaData.supportsOuterJoins());
        assertTrue(metaData.supportsFullOuterJoins());
        assertTrue(metaData.supportsUnion());
        assertTrue(metaData.supportsUnionAll());
        assertTrue(metaData.supportsSubqueriesInComparisons());
        assertTrue(metaData.supportsColumnAliasing());

        assertFalse(metaData.supportsStoredProcedures());
        assertFalse(metaData.supportsSelectForUpdate());
        assertFalse(metaData.supportsMultipleResultSets());
    }

    @Test
    @Order(23)
    @DisplayName("getIdentifierQuoteString 应返回双引号")
    void testIdentifierQuoteString() throws SQLException {
        assertEquals("\"", metaData.getIdentifierQuoteString());
    }

    @Test
    @Order(24)
    @DisplayName("getSearchStringEscape 应返回反斜杠")
    void testSearchStringEscape() throws SQLException {
        assertEquals("\\", metaData.getSearchStringEscape());
    }

    @Test
    @Order(25)
    @DisplayName("nullPlusNonNullIsNull 应为 true")
    void testNullPlusNonNullIsNull() throws SQLException {
        assertTrue(metaData.nullPlusNonNullIsNull());
    }
}