package io.github.zongkx;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.stream.Collectors;

public class DuckDBDatabaseMetaData implements DatabaseMetaData {

    private final DuckDBJdbcConnection connection;

    public DuckDBDatabaseMetaData(DuckDBJdbcConnection connection) {
        this.connection = connection;
    }

    // ========== 辅助方法 ==========

    /**
     * 若 pattern 为 null 则返回 %，否则原样返回
     */
    private static String nullPatternToWildcard(String pattern) {
        return pattern == null ? "%" : pattern;
    }

    /**
     * 追加 catalog 精确匹配条件，返回是否有参数需要绑定
     */
    private static boolean appendEqualsQual(StringBuilder sb, String colName, String value) {
        if (value != null) {
            sb.append("AND ");
            sb.append(colName);
            if (value.isEmpty()) {
                sb.append(" IS NULL ");
            } else {
                sb.append(" = ? ");
                return true;   // 需要绑定参数
            }
        }
        return false;
    }

    /**
     * 追加 schema 模式匹配条件，返回是否有参数需要绑定
     */
    private static boolean appendLikeQual(StringBuilder sb, String colName, String pattern) {
        if (pattern != null) {
            sb.append("AND ");
            sb.append(colName);
            if (pattern.isEmpty()) {
                sb.append(" IS NULL ");
            } else {
                sb.append(" LIKE ? ESCAPE '\\' ");
                return true;
            }
        }
        return false;
    }

    // ========== 关键元数据方法 ==========

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
            throws SQLException {
        StringBuilder sb = new StringBuilder(512);

        // 使用官方驱动的查询结构，从 duckdb_tables() 和 duckdb_views() 联合获取
        sb.append("SELECT ");
        sb.append("table_catalog AS 'TABLE_CAT', ");
        sb.append("table_schema AS 'TABLE_SCHEM', ");
        sb.append("table_name AS 'TABLE_NAME', ");
        sb.append("table_type AS 'TABLE_TYPE', ");
        sb.append("NULL::VARCHAR AS 'REMARKS', ");
        sb.append("NULL::VARCHAR AS 'TYPE_CAT', ");
        sb.append("NULL::VARCHAR AS 'TYPE_SCHEM', ");
        sb.append("NULL::VARCHAR AS 'TYPE_NAME', ");
        sb.append("NULL::VARCHAR AS 'SELF_REFERENCING_COL_NAME', ");
        sb.append("NULL::VARCHAR AS 'REF_GENERATION' ");
        sb.append("FROM (");
        sb.append("  SELECT database_name AS table_catalog, schema_name AS table_schema, table_name, ");
        sb.append("    CASE WHEN (\"temporary\") THEN 'LOCAL TEMPORARY' ");
        sb.append("         WHEN (\"internal\") THEN 'SYSTEM TABLE' ");
        sb.append("         ELSE 'TABLE' END AS table_type ");
        sb.append("  FROM duckdb_tables() ");
        sb.append("  UNION ALL ");
        sb.append("  SELECT database_name, schema_name, view_name, ");
        sb.append("    CASE WHEN (\"internal\") THEN 'SYSTEM VIEW' ");
        sb.append("         ELSE 'VIEW' END ");
        sb.append("  FROM duckdb_views() ");
        sb.append(") x ");
        sb.append("WHERE table_name LIKE ? ESCAPE '\\' ");

        boolean hasCatalogParam = appendEqualsQual(sb, "table_catalog", catalog);
        boolean hasSchemaParam = appendLikeQual(sb, "table_schema", schemaPattern);

        if (types != null && types.length > 0) {
            sb.append("AND table_type IN (");
            for (int i = 0; i < types.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append('?');
            }
            sb.append(") ");
        }

        sb.append("ORDER BY table_type, table_catalog, table_schema, table_name");

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        int idx = 1;
        ps.setString(idx++, nullPatternToWildcard(tableNamePattern));

        if (hasCatalogParam) ps.setString(idx++, catalog);
        if (hasSchemaParam) ps.setString(idx++, schemaPattern);

        if (types != null) {
            for (String type : types) {
                ps.setString(idx++, type);
            }
        }

        // 注意：你的 PreparedStatement 可能不支持 closeOnCompletion，可忽略或自己实现
        return ps.executeQuery();
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return null;
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        return null;
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        return null;
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
                                String columnNamePattern) throws SQLException {
        StringBuilder sb = new StringBuilder(512);

        sb.append("SELECT ");
        sb.append("database_name AS 'TABLE_CAT', ");
        sb.append("schema_name AS 'TABLE_SCHEM', ");
        sb.append("table_name AS 'TABLE_NAME', ");
        sb.append("column_name AS 'COLUMN_NAME', ");
        sb.append(makeTypeDataMap("c.data_type", "DATA_TYPE")).append(", ");
        sb.append("c.data_type AS 'TYPE_NAME', ");
        sb.append("numeric_precision AS 'COLUMN_SIZE', ");
        sb.append("NULL AS 'BUFFER_LENGTH', ");
        sb.append("numeric_scale AS 'DECIMAL_DIGITS', ");
        sb.append("10 AS 'NUM_PREC_RADIX', ");
        sb.append("CASE WHEN is_nullable THEN 1 ELSE 0 END AS 'NULLABLE', ");
        sb.append("comment AS 'REMARKS', ");
        sb.append("column_default AS 'COLUMN_DEF', ");
        sb.append("NULL AS 'SQL_DATA_TYPE', ");
        sb.append("NULL AS 'SQL_DATETIME_SUB', ");
        sb.append("NULL AS 'CHAR_OCTET_LENGTH', ");
        sb.append("column_index AS 'ORDINAL_POSITION', ");
        sb.append("CASE WHEN is_nullable THEN 'YES' ELSE 'NO' END AS 'IS_NULLABLE', ");
        sb.append("NULL AS 'SCOPE_CATALOG', ");
        sb.append("NULL AS 'SCOPE_SCHEMA', ");
        sb.append("NULL AS 'SCOPE_TABLE', ");
        sb.append("NULL AS 'SOURCE_DATA_TYPE', ");
        sb.append("'' AS 'IS_AUTOINCREMENT', ");
        sb.append("'' AS 'IS_GENERATEDCOLUMN' ");
        sb.append("FROM duckdb_columns() c ");
        sb.append("WHERE TRUE ");

        boolean hasCatalogParam = appendEqualsQual(sb, "database_name", catalog);
        boolean hasSchemaParam = appendLikeQual(sb, "schema_name", schemaPattern);
        sb.append("AND table_name LIKE ? ESCAPE '\\' ");
        sb.append("AND column_name LIKE ? ESCAPE '\\' ");

        sb.append("ORDER BY \"TABLE_CAT\", \"TABLE_SCHEM\", \"TABLE_NAME\", column_index");

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        int idx = 1;

        if (hasCatalogParam) ps.setString(idx++, catalog);
        if (hasSchemaParam) ps.setString(idx++, schemaPattern);
        ps.setString(idx++, nullPatternToWildcard(tableNamePattern));
        ps.setString(idx++, nullPatternToWildcard(columnNamePattern));

        return ps.executeQuery();
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable, String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        return null;
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        return null;
    }

    @Override
    public boolean supportsResultSetType(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
        return false;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) throws SQLException {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() throws SQLException {
        return false;
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws SQLException {
        return null;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return null;
    }

    @Override
    public boolean supportsSavepoints() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws SQLException {
        return false;
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) throws SQLException {
        return null;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) throws SQLException {
        return false;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return 0;
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return 0;
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return 0;
    }

    @Override
    public int getJDBCMajorVersion() throws SQLException {
        return 0;
    }

    @Override
    public int getJDBCMinorVersion() throws SQLException {
        return 0;
    }

    @Override
    public int getSQLStateType() throws SQLException {
        return 0;
    }

    @Override
    public boolean locatorsUpdateCopy() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsStatementPooling() throws SQLException {
        return false;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        return null;
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        return null;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        return false;
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return null;
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException {
        return null;
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        return null;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws SQLException {
        return false;
    }

    /**
     * 将 DuckDB 类型名映射到 JDBC Types 的 CASE 表达式
     */
    private String makeTypeDataMap(String srcColumn, String asName) {
        // 此处省略完整映射，可参考官方驱动或按需扩展几个常用类型
        String simpleMap = Arrays.stream(DuckDBColumnType.values())
                .map(t -> String.format("WHEN '%s' THEN %d ",
                        t.name().replace("_", " "), t.getJdbcType()))
                .collect(Collectors.joining());
        return String.format("CASE %s %s ELSE %d END AS %s", srcColumn, simpleMap, Types.OTHER, asName);
    }

    // ========== 其他方法的合规补全（部分） ==========

    @Override
    public boolean allProceduresAreCallable() throws SQLException {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() throws SQLException {
        return false;
    }

    @Override
    public String getURL() throws SQLException {
        return "";
    }

    @Override
    public String getUserName() throws SQLException {
        return "";
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() throws SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException {
        return false;
    }

    @Override
    public String getDatabaseProductName() throws SQLException {
        return "DuckDB";
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        try (Statement s = this.connection.createStatement(); ResultSet rs = s.executeQuery("PRAGMA version")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Override
    public String getDriverName() throws SQLException {
        return "DuckDBJ";
    }

    @Override
    public String getDriverVersion() throws SQLException {
        return "1.0";
    }

    @Override
    public int getDriverMajorVersion() {
        return 1;
    }

    @Override
    public int getDriverMinorVersion() {
        return 0;
    }

    @Override
    public boolean usesLocalFiles() throws SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws SQLException {
        return true;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
        return true;
    }

    @Override
    public String getIdentifierQuoteString() throws SQLException {
        return "\"";
    }

    @Override
    public String getSQLKeywords() throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT keyword_name FROM duckdb_keywords()")) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) sb.append(rs.getString(1)).append(',');
            return sb.toString();
        }
    }

    @Override
    public String getNumericFunctions() throws SQLException {
        return getFunctionStringByFirstParam("DECIMAL");
    }

    @Override
    public String getStringFunctions() throws SQLException {
        return getFunctionStringByFirstParam("VARCHAR");
    }

    @Override
    public String getTimeDateFunctions() throws SQLException {
        return getFunctionStringByFirstParam("TIME");
    }

    private String getFunctionStringByFirstParam(String paramHint) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT DISTINCT function_name FROM duckdb_functions() WHERE parameter_types[1] LIKE '"
                             + paramHint + "%'")) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) sb.append(rs.getString(1)).append(',');
            return sb.toString();
        }
    }

    @Override
    public String getSystemFunctions() throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT DISTINCT function_name FROM duckdb_functions() WHERE length(parameter_types) = 0")) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) sb.append(rs.getString(1)).append(',');
            return sb.toString();
        }
    }

    @Override
    public String getSearchStringEscape() throws SQLException {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() throws SQLException {
        return "";
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsColumnAliasing() throws SQLException {
        return false;
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsConvert() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) throws SQLException {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOrderByUnrelated() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsGroupBy() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsGroupByUnrelated() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsLikeEscapeClause() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleResultSets() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsNonNullableColumns() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException {
        return false;
    }

    @Override
    public String getSchemaTerm() throws SQLException {
        return "";
    }

    @Override
    public String getProcedureTerm() throws SQLException {
        return "";
    }

    @Override
    public String getCatalogTerm() throws SQLException {
        return "";
    }

    @Override
    public boolean isCatalogAtStart() throws SQLException {
        return false;
    }

    @Override
    public String getCatalogSeparator() throws SQLException {
        return "";
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInExists() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInIns() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsUnion() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsUnionAll() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
        return false;
    }

    @Override
    public int getMaxBinaryLiteralLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxConnections() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxIndexLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxRowSize() throws SQLException {
        return 0;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
        return false;
    }

    @Override
    public int getMaxStatementLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxStatements() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() throws SQLException {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() throws SQLException {
        return 0;
    }

    @Override
    public int getDefaultTransactionIsolation() throws SQLException {
        return 0;
    }

    @Override
    public boolean supportsTransactions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
        return level < Connection.TRANSACTION_SERIALIZABLE;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
        return true;

    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
        return false;
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        Statement statement = connection.createStatement();
        statement.closeOnCompletion();
        return statement.executeQuery("SELECT NULL WHERE FALSE");
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException {
        Statement statement = connection.createStatement();
        statement.closeOnCompletion();
        return statement.executeQuery("SELECT NULL WHERE FALSE");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return unwrap(this, iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    static <T> T unwrap(Object obj, Class<T> iface) throws SQLException {
        if (!iface.isInstance(obj)) {
            throw new SQLException(obj.getClass().getName() + " not unwrappable from " + iface.getName());
        }
        return (T) obj;
    }
}