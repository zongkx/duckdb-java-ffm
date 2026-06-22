package io.github.zongkx;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DuckDBDatabaseMetaData implements DatabaseMetaData {

    private final DuckDBJdbcConnection connection;

    private static final String DATA_MAP = buildDataMap();
    private static final String TRAILING_COMMA = ", ";

    public DuckDBDatabaseMetaData(DuckDBJdbcConnection connection) {
        this.connection = connection;
    }

    private static String nullPatternToWildcard(String pattern) {
        return pattern == null ? "%" : pattern;
    }

    private static boolean appendEqualsQual(StringBuilder sb, String colName, String value) {
        boolean hasParam = false;
        if (value != null) {
            sb.append("AND ");
            sb.append(colName);
            if (value.isEmpty()) {
                sb.append(" IS NULL ");
            } else {
                sb.append(" = ? ");
                hasParam = true;
            }
        }
        return hasParam;
    }

    private static boolean appendLikeQual(StringBuilder sb, String colName, String pattern) {
        boolean hasParam = false;
        if (pattern != null) {
            sb.append("AND ");
            sb.append(colName);
            if (pattern.isEmpty()) {
                sb.append(" IS NULL ");
            } else {
                sb.append(" LIKE ? ESCAPE '\\' ");
                hasParam = true;
            }
        }
        return hasParam;
    }

    private static String buildDataMap() {
        // DuckDB type names as they appear in duckdb_columns().data_type
        // (after regexp_replace to remove parameter parts)
        Map<String, Integer> typeMap = new LinkedHashMap<>();
        typeMap.put("BOOLEAN", Types.BIT);
        typeMap.put("TINYINT", Types.TINYINT);
        typeMap.put("SMALLINT", Types.SMALLINT);
        typeMap.put("INTEGER", Types.INTEGER);
        typeMap.put("BIGINT", Types.BIGINT);
        typeMap.put("HUGEINT", Types.NUMERIC);
        typeMap.put("FLOAT", Types.FLOAT);
        typeMap.put("REAL", Types.REAL);
        typeMap.put("DOUBLE", Types.DOUBLE);
        typeMap.put("DECIMAL", Types.DECIMAL);
        typeMap.put("VARCHAR", Types.VARCHAR);
        typeMap.put("CHAR", Types.CHAR);
        typeMap.put("BLOB", Types.BINARY);
        typeMap.put("DATE", Types.DATE);
        typeMap.put("TIME", Types.TIME);
        typeMap.put("TIMESTAMP", Types.TIMESTAMP);
        typeMap.put("TIMESTAMP WITH TIME ZONE", Types.TIMESTAMP_WITH_TIMEZONE);
        typeMap.put("INTERVAL", Types.OTHER);
        typeMap.put("UUID", Types.OTHER);
        typeMap.put("JSON", Types.OTHER);
        typeMap.put("LIST", Types.ARRAY);
        typeMap.put("STRUCT", Types.STRUCT);
        typeMap.put("MAP", Types.OTHER);
        typeMap.put("ENUM", Types.VARCHAR); // treated as VARCHAR in base type
        typeMap.put("UNION", Types.OTHER);
        // Add any additional types your DuckDB version uses

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : typeMap.entrySet()) {
            sb.append(String.format(" WHEN '%s' THEN %d", entry.getKey(), entry.getValue()));
        }
        return sb.toString();
    }

    private String makeTypeDataMap(String srcColumn, String asName) {
        return String.format("CASE %s %s ELSE %d END AS %s", srcColumn, DATA_MAP, Types.OTHER, asName);
    }

    private void appendAndQual(StringBuilder sb, boolean needed) {
        if (needed) {
            sb.append("  AND ");
        } else {
            sb.append(" ");
        }
    }

    // ----------------------------------------------------------------------
    //  DatabaseMetaData implementation
    // ----------------------------------------------------------------------

    @Override
    public boolean allProceduresAreCallable() throws SQLException {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() throws SQLException {
        return true;
    }

    @Override
    public String getURL() throws SQLException {
        // Adapt to your DuckDBJdbcConnection's URL field/method
        try {
            return connection.getMetaData().getURL();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String getUserName() throws SQLException {
        return "";
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return connection.isReadOnly();
    }

    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        return true;
    }

    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() throws SQLException {
        return true;
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
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA version")) {
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
        return true;
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
            while (rs.next()) {
                sb.append(rs.getString(1)).append(',');
            }
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
    public String getSystemFunctions() throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT DISTINCT function_name FROM duckdb_functions() WHERE length(parameter_types) = 0")) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getString(1)).append(',');
            }
            return sb.toString();
        }
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
            while (rs.next()) {
                sb.append(rs.getString(1)).append(',');
            }
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
        return true;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() throws SQLException {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException {
        return true;
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
        return true;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupBy() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsLikeEscapeClause() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMultipleResultSets() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsNonNullableColumns() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsANSI92FullSQL() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsFullOuterJoins() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException {
        return true;
    }

    @Override
    public String getSchemaTerm() throws SQLException {
        return "schema";
    }

    @Override
    public String getProcedureTerm() throws SQLException {
        return "procedure";
    }

    @Override
    public String getCatalogTerm() throws SQLException {
        return "catalog";
    }

    @Override
    public boolean isCatalogAtStart() throws SQLException {
        return true;
    }

    @Override
    public String getCatalogSeparator() throws SQLException {
        return ".";
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
        return true;
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
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsUnion() throws SQLException {
        return true;
    }

    @Override
    public boolean supportsUnionAll() throws SQLException {
        return true;
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
        return Connection.TRANSACTION_REPEATABLE_READ;
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

    // ----------------------------------------------------------------------
    //  Catalog / Schema / Table listing methods
    // ----------------------------------------------------------------------

    @Override
    public ResultSet getCatalogs() throws SQLException {
        Statement stmt = connection.createStatement();
        stmt.closeOnCompletion();
        return stmt.executeQuery(
                "SELECT DISTINCT catalog_name AS 'TABLE_CAT' FROM information_schema.schemata ORDER BY \"TABLE_CAT\"");
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        Statement stmt = connection.createStatement();
        stmt.closeOnCompletion();
        return stmt.executeQuery(
                "SELECT schema_name AS 'TABLE_SCHEM', catalog_name AS 'TABLE_CATALOG' FROM information_schema.schemata ORDER BY \"TABLE_CATALOG\", \"TABLE_SCHEM\"");
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        StringBuilder sb = new StringBuilder(512);
        sb.append("SELECT schema_name AS 'TABLE_SCHEM', catalog_name AS 'TABLE_CATALOG' ");
        sb.append("FROM information_schema.schemata WHERE TRUE ");
        boolean hasCatalogParam = appendEqualsQual(sb, "catalog_name", catalog);
        boolean hasSchemaParam = appendLikeQual(sb, "schema_name", schemaPattern);
        sb.append("ORDER BY \"TABLE_CATALOG\", \"TABLE_SCHEM\"");

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        int idx = 1;
        if (hasCatalogParam) ps.setString(idx++, catalog);
        if (hasSchemaParam) ps.setString(idx++, schemaPattern);
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        String[] types = {"TABLE", "LOCAL TEMPORARY", "VIEW", "SYSTEM VIEW"};
        StringBuilder sb = new StringBuilder(128);
        boolean first = true;
        for (String t : types) {
            if (!first) sb.append("\nUNION ALL\n");
            sb.append("SELECT '").append(t).append("'");
            if (first) {
                sb.append(" AS 'TABLE_TYPE'");
                first = false;
            }
        }
        sb.append("\nORDER BY TABLE_TYPE");
        Statement stmt = connection.createStatement();
        stmt.closeOnCompletion();
        return stmt.executeQuery(sb.toString());
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
            throws SQLException {
        StringBuilder sb = new StringBuilder(512);
        sb.append("SELECT ");
        sb.append("table_catalog AS 'TABLE_CAT', ");
        sb.append("table_schema AS 'TABLE_SCHEM', ");
        sb.append("table_name AS 'TABLE_NAME', ");
        sb.append("table_type AS 'TABLE_TYPE', ");
        sb.append("comment AS 'REMARKS', ");
        sb.append("NULL::VARCHAR AS 'TYPE_CAT', ");
        sb.append("NULL::VARCHAR AS 'TYPE_SCHEM', ");
        sb.append("NULL::VARCHAR AS 'TYPE_NAME', ");
        sb.append("NULL::VARCHAR AS 'SELF_REFERENCING_COL_NAME', ");
        sb.append("NULL::VARCHAR AS 'REF_GENERATION' ");
        sb.append("FROM (");
        sb.append("  SELECT database_name AS table_catalog, schema_name AS table_schema, table_name, ");
        sb.append("    CASE WHEN (\"temporary\") THEN 'LOCAL TEMPORARY' ");
        sb.append("         WHEN (\"internal\") THEN 'SYSTEM TABLE' ");
        sb.append("         ELSE 'TABLE' END AS table_type, comment ");
        sb.append("  FROM duckdb_tables() ");
        sb.append("  UNION ALL ");
        sb.append("  SELECT database_name, schema_name, view_name, ");
        sb.append("    CASE WHEN (\"internal\") THEN 'SYSTEM VIEW' ");
        sb.append("         ELSE 'VIEW' END, comment ");
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
        ps.closeOnCompletion();
        return ps.executeQuery();
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
        sb.append(makeTypeDataMap("regexp_replace(c.data_type, '\\\\(.*\\\\)', '')", "DATA_TYPE")).append(", ");
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
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table,
                                         String columnNamePattern) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
            throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        StringBuilder sb = new StringBuilder(512);
        sb.append("WITH constraint_columns AS ( ");
        sb.append("  SELECT ");
        sb.append("    database_name AS \"TABLE_CAT\", ");
        sb.append("    schema_name AS \"TABLE_SCHEM\", ");
        sb.append("    table_name AS \"TABLE_NAME\", ");
        sb.append("    unnest(constraint_column_names) AS \"COLUMN_NAME\", ");
        sb.append("    NULL::VARCHAR AS \"PK_NAME\" ");
        sb.append("  FROM duckdb_constraints ");
        sb.append("  WHERE constraint_type = 'PRIMARY KEY' ");
        boolean hasCatalogParam = appendEqualsQual(sb, "database_name", catalog);
        boolean hasSchemaParam = appendEqualsQual(sb, "schema_name", schema);
        sb.append("    AND table_name = ? ");
        sb.append(") ");
        sb.append("SELECT ");
        sb.append("  \"TABLE_CAT\", ");
        sb.append("  \"TABLE_SCHEM\", ");
        sb.append("  \"TABLE_NAME\", ");
        sb.append("  \"COLUMN_NAME\", ");
        sb.append("  CAST(ROW_NUMBER() OVER (PARTITION BY \"TABLE_CAT\", \"TABLE_SCHEM\", \"TABLE_NAME\") AS INT) AS \"KEY_SEQ\", ");
        sb.append("  \"PK_NAME\" ");
        sb.append("FROM constraint_columns ");
        sb.append("ORDER BY \"TABLE_CAT\", \"TABLE_SCHEM\", \"TABLE_NAME\", \"KEY_SEQ\"");

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        int idx = 1;
        if (hasCatalogParam) ps.setString(idx++, catalog);
        if (hasSchemaParam) ps.setString(idx++, schema);
        ps.setString(idx++, table);
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return getCrossReference(null, null, null, catalog, schema, table);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return getCrossReference(catalog, schema, table, null, null, null);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
                                       String foreignCatalog, String foreignSchema, String foreignTable)
            throws SQLException {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("SELECT ");
        sb.append("  pk_tc.table_catalog AS PKTABLE_CAT, ");
        sb.append("  pk_tc.table_schema AS PKTABLE_SCHEM, ");
        sb.append("  pk_tc.table_name AS PKTABLE_NAME, ");
        sb.append("  pk_kcu.column_name AS PKCOLUMN_NAME, ");
        sb.append("  fk_tc.table_catalog AS FKTABLE_CAT, ");
        sb.append("  fk_tc.table_schema AS FKTABLE_SCHEM, ");
        sb.append("  fk_tc.table_name AS FKTABLE_NAME, ");
        sb.append("  fk_kcu.column_name AS FKCOLUMN_NAME, ");
        sb.append("  fk_kcu.ordinal_position AS KEY_SEQ, ");
        sb.append("  CASE rc.update_rule ");
        sb.append("    WHEN 'CASCADE' THEN ").append(DatabaseMetaData.importedKeyCascade).append(" ");
        sb.append("    WHEN 'RESTRICT' THEN ").append(DatabaseMetaData.importedKeyRestrict).append(" ");
        sb.append("    WHEN 'SET NULL' THEN ").append(DatabaseMetaData.importedKeySetNull).append(" ");
        sb.append("    WHEN 'SET DEFAULT' THEN ").append(DatabaseMetaData.importedKeySetDefault).append(" ");
        sb.append("    ELSE ").append(DatabaseMetaData.importedKeyNoAction).append(" ");
        sb.append("  END AS UPDATE_RULE, ");
        sb.append("  CASE rc.delete_rule ");
        sb.append("    WHEN 'CASCADE' THEN ").append(DatabaseMetaData.importedKeyCascade).append(" ");
        sb.append("    WHEN 'RESTRICT' THEN ").append(DatabaseMetaData.importedKeyRestrict).append(" ");
        sb.append("    WHEN 'SET NULL' THEN ").append(DatabaseMetaData.importedKeySetNull).append(" ");
        sb.append("    WHEN 'SET DEFAULT' THEN ").append(DatabaseMetaData.importedKeySetDefault).append(" ");
        sb.append("    ELSE ").append(DatabaseMetaData.importedKeyNoAction).append(" ");
        sb.append("  END AS DELETE_RULE, ");
        sb.append("  rc.constraint_name AS FK_NAME, ");
        sb.append("  rc.unique_constraint_name AS PK_NAME, ");
        sb.append("  CASE ");
        sb.append("    WHEN fk_tc.is_deferrable = 'YES' AND fk_tc.initially_deferred = 'YES' THEN ")
                .append(DatabaseMetaData.importedKeyInitiallyDeferred).append(" ");
        sb.append("    WHEN fk_tc.is_deferrable = 'YES' AND fk_tc.initially_deferred = 'NO' THEN ")
                .append(DatabaseMetaData.importedKeyInitiallyImmediate).append(" ");
        sb.append("    ELSE ").append(DatabaseMetaData.importedKeyNotDeferrable).append(" ");
        sb.append("  END AS DEFERRABILITY ");
        sb.append("FROM information_schema.referential_constraints rc ");
        sb.append("  JOIN information_schema.table_constraints fk_tc ");
        sb.append("    ON fk_tc.constraint_catalog = rc.constraint_catalog ");
        sb.append("    AND fk_tc.constraint_schema = rc.constraint_schema ");
        sb.append("    AND fk_tc.constraint_name = rc.constraint_name ");
        sb.append("  JOIN information_schema.key_column_usage fk_kcu ");
        sb.append("    ON fk_kcu.constraint_catalog = rc.constraint_catalog ");
        sb.append("    AND fk_kcu.constraint_schema = rc.constraint_schema ");
        sb.append("    AND fk_kcu.constraint_name = rc.constraint_name ");
        sb.append("  JOIN information_schema.table_constraints pk_tc ");
        sb.append("    ON pk_tc.constraint_catalog = rc.unique_constraint_catalog ");
        sb.append("    AND pk_tc.constraint_schema = rc.unique_constraint_schema ");
        sb.append("    AND pk_tc.constraint_name = rc.unique_constraint_name ");
        sb.append("  JOIN information_schema.key_column_usage pk_kcu ");
        sb.append("    ON pk_kcu.constraint_catalog = pk_tc.constraint_catalog ");
        sb.append("    AND pk_kcu.constraint_schema = pk_tc.constraint_schema ");
        sb.append("    AND pk_kcu.constraint_name = pk_tc.constraint_name ");
        sb.append("    AND pk_kcu.ordinal_position = fk_kcu.ordinal_position ");

        boolean andNeeded = false;
        if (parentCatalog != null || parentSchema != null || parentTable != null ||
                foreignCatalog != null || foreignSchema != null || foreignTable != null) {
            sb.append("WHERE ");
        }
        if (parentCatalog != null) {
            appendAndQual(sb, andNeeded);
            sb.append("pk_tc.table_catalog = ? ");
            andNeeded = true;
        }
        if (parentSchema != null) {
            appendAndQual(sb, andNeeded);
            sb.append("pk_tc.table_schema = ? ");
            andNeeded = true;
        }
        if (parentTable != null) {
            appendAndQual(sb, andNeeded);
            sb.append("pk_tc.table_name = ? ");
            andNeeded = true;
        }
        if (foreignCatalog != null) {
            appendAndQual(sb, andNeeded);
            sb.append("fk_tc.table_catalog = ? ");
            andNeeded = true;
        }
        if (foreignSchema != null) {
            appendAndQual(sb, andNeeded);
            sb.append("fk_tc.table_schema = ? ");
            andNeeded = true;
        }
        if (foreignTable != null) {
            appendAndQual(sb, andNeeded);
            sb.append("fk_tc.table_name = ? ");
            andNeeded = true;
        }
        sb.append("ORDER BY ");
        if (foreignTable != null) { // imported keys
            sb.append("PKTABLE_CAT, PKTABLE_SCHEM, PKTABLE_NAME, KEY_SEQ, FKTABLE_CAT, FKTABLE_SCHEM, FKTABLE_NAME");
        } else { // exported keys or full cross-reference
            sb.append("FKTABLE_CAT, FKTABLE_SCHEM, FKTABLE_NAME, KEY_SEQ, PKTABLE_CAT, PKTABLE_SCHEM, PKTABLE_NAME");
        }

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        int idx = 1;
        if (parentCatalog != null) ps.setString(idx++, parentCatalog);
        if (parentSchema != null) ps.setString(idx++, parentSchema);
        if (parentTable != null) ps.setString(idx++, parentTable);
        if (foreignCatalog != null) ps.setString(idx++, foreignCatalog);
        if (foreignSchema != null) ps.setString(idx++, foreignSchema);
        if (foreignTable != null) ps.setString(idx++, foreignTable);
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        List<TypeInfo> typeList = new ArrayList<>();
        typeList.add(new TypeInfo("BOOLEAN", Types.BIT, DatabaseMetaData.typePredBasic));
        typeList.add(new TypeInfo("TINYINT", Types.TINYINT, DatabaseMetaData.typePredBasic));
        typeList.add(new TypeInfo("SMALLINT", Types.SMALLINT, DatabaseMetaData.typePredBasic));
        typeList.add(new TypeInfo("INTEGER", Types.INTEGER, DatabaseMetaData.typePredBasic));
        typeList.add(new TypeInfo("BIGINT", Types.BIGINT, DatabaseMetaData.typePredBasic));
        typeList.add(new TypeInfo("FLOAT", Types.FLOAT, DatabaseMetaData.typePredBasic));
        typeList.add(new TypeInfo("REAL", Types.REAL, DatabaseMetaData.typePredBasic));
        typeList.add(new TypeInfo("DOUBLE", Types.DOUBLE, DatabaseMetaData.typePredBasic));
        typeList.add(new TypeInfo("DECIMAL", Types.DECIMAL, DatabaseMetaData.typePredBasic, 0, 38));
        typeList.add(new TypeInfo("VARCHAR", Types.VARCHAR, DatabaseMetaData.typePredChar));
        typeList.add(new TypeInfo("VARCHAR", Types.LONGVARCHAR, DatabaseMetaData.typePredChar));
        typeList.add(new TypeInfo("DATE", Types.DATE, DatabaseMetaData.typePredBasic));
        typeList.add(new TypeInfo("TIME", Types.TIME, DatabaseMetaData.typePredBasic));
        typeList.add(new TypeInfo("TIMESTAMP", Types.TIMESTAMP, DatabaseMetaData.typePredBasic));
        typeList.add(new TypeInfo("BLOB", Types.BINARY, DatabaseMetaData.typePredChar));
        typeList.add(new TypeInfo("BLOB", Types.VARBINARY, DatabaseMetaData.typePredChar));
        typeList.add(new TypeInfo("BLOB", Types.LONGVARBINARY, DatabaseMetaData.typePredChar));

        StringBuilder sb = new StringBuilder(512);
        boolean first = true;
        for (TypeInfo ti : typeList) {
            if (first) {
                sb.append("SELECT ");
                first = false;
            } else {
                sb.append("UNION ALL SELECT ");
            }
            sb.append("'").append(ti.name).append("'::VARCHAR AS TYPE_NAME, ");
            sb.append(ti.sqlType).append("::INTEGER AS DATA_TYPE, ");
            sb.append(ti.precision).append("::INTEGER AS PRECISION, ");
            sb.append("NULL::VARCHAR AS LITERAL_PREFIX, ");
            sb.append("NULL::VARCHAR AS LITERAL_SUFFIX, ");
            sb.append("NULL::VARCHAR AS CREATE_PARAMS, ");
            sb.append(DatabaseMetaData.typeNullableUnknown).append("::SMALLINT AS NULLABLE, ");
            sb.append("TRUE::BOOL AS CASE_SENSITIVE, ");
            sb.append(ti.searchable).append("::SMALLINT AS SEARCHABLE, ");
            sb.append("FALSE::BOOL AS UNSIGNED_ATTRIBUTE, ");
            sb.append("FALSE::BOOL AS FIXED_PREC_SCALE, ");
            sb.append("FALSE::BOOL AS AUTO_INCREMENT, ");
            sb.append("NULL::VARCHAR AS LOCAL_TYPE_NAME, ");
            sb.append("0::SMALLINT AS MINIMUM_SCALE, ");
            sb.append(ti.scale).append("::SMALLINT AS MAXIMUM_SCALE, ");
            sb.append("0::INTEGER AS SQL_DATA_TYPE, ");
            sb.append("0::INTEGER AS SQL_DATETIME_SUB, ");
            sb.append("10::INTEGER AS NUM_PREC_RADIX ");
        }
        sb.append(" ORDER BY DATA_TYPE");

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    private static class TypeInfo {
        final String name;
        final int sqlType;
        final int searchable;
        final int precision;
        final int scale;

        TypeInfo(String name, int sqlType, int searchable) {
            this(name, sqlType, searchable, 0, 0);
        }

        TypeInfo(String name, int sqlType, int searchable, int precision, int scale) {
            this.name = name;
            this.sqlType = sqlType;
            this.searchable = searchable;
            this.precision = precision;
            this.scale = scale;
        }
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique,
                                  boolean approximate) throws SQLException {
        StringBuilder sb = new StringBuilder(512);
        sb.append("SELECT ");
        sb.append("database_name AS 'TABLE_CAT', ");
        sb.append("schema_name AS 'TABLE_SCHEM', ");
        sb.append("table_name AS 'TABLE_NAME', ");
        sb.append("index_name AS 'INDEX_NAME', ");
        sb.append("CASE WHEN is_unique THEN 0 ELSE 1 END AS 'NON_UNIQUE', ");
        sb.append("NULL AS 'TYPE', ");
        sb.append("NULL AS 'ORDINAL_POSITION', ");
        sb.append("NULL AS 'COLUMN_NAME', ");
        sb.append("NULL AS 'ASC_OR_DESC', ");
        sb.append("NULL AS 'CARDINALITY', ");
        sb.append("NULL AS 'PAGES', ");
        sb.append("NULL AS 'FILTER_CONDITION' ");
        sb.append("FROM duckdb_indexes() WHERE TRUE ");
        boolean hasCatalogParam = appendEqualsQual(sb, "database_name", catalog);
        boolean hasSchemaParam = appendEqualsQual(sb, "schema_name", schema);
        sb.append("AND table_name = ? ");
        if (unique) sb.append("AND is_unique = TRUE ");
        sb.append("ORDER BY \"TABLE_CAT\", \"TABLE_SCHEM\", \"TABLE_NAME\", \"NON_UNIQUE\", \"INDEX_NAME\"");

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        int idx = 1;
        if (hasCatalogParam) ps.setString(idx++, catalog);
        if (hasSchemaParam) ps.setString(idx++, schema);
        ps.setString(idx++, table);
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    @Override
    public boolean supportsResultSetType(int type) throws SQLException {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean ownDeletesAreVisible(int type) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean ownInsertsAreVisible(int type) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean othersDeletesAreVisible(int type) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean othersInsertsAreVisible(int type) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean updatesAreDetected(int type) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean deletesAreDetected(int type) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean insertsAreDetected(int type) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean supportsBatchUpdates() throws SQLException {
        return true;
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types)
            throws SQLException {
        StringBuilder sb = new StringBuilder(512);
        String udtDataTypeExpr = "CASE WHEN logical_type IN ('STRUCT', 'UNION') THEN " + Types.STRUCT +
                " ELSE " + Types.DISTINCT + " END";
        String baseTypeExpr = "CASE WHEN logical_type IN ('STRUCT', 'UNION') THEN NULL::SMALLINT " +
                "WHEN logical_type = 'ENUM' THEN " + Types.VARCHAR + "::SMALLINT " +
                "ELSE CAST(CASE logical_type " + DATA_MAP + " ELSE " + Types.OTHER +
                " END AS SMALLINT) END";

        sb.append("SELECT ");
        sb.append("database_name AS 'TYPE_CAT', ");
        sb.append("schema_name AS 'TYPE_SCHEM', ");
        sb.append("type_name AS 'TYPE_NAME', ");
        sb.append("NULL::VARCHAR AS 'CLASS_NAME', ");
        sb.append(udtDataTypeExpr).append(" AS 'DATA_TYPE', ");
        sb.append("comment AS 'REMARKS', ");
        sb.append(baseTypeExpr).append(" AS 'BASE_TYPE' ");
        sb.append("FROM duckdb_types() WHERE internal = FALSE ");
        boolean hasCatalogParam = appendEqualsQual(sb, "database_name", catalog);
        boolean hasSchemaParam = appendLikeQual(sb, "schema_name", schemaPattern);
        sb.append("AND type_name LIKE ? ESCAPE '\\' ");

        if (types != null && types.length > 0) {
            sb.append("AND (").append(udtDataTypeExpr).append(") IN (");
            for (int i = 0; i < types.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append('?');
            }
            sb.append(") ");
        }

        sb.append("ORDER BY \"DATA_TYPE\", \"TYPE_CAT\", \"TYPE_SCHEM\", \"TYPE_NAME\"");

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        int idx = 1;
        if (hasCatalogParam) ps.setString(idx++, catalog);
        if (hasSchemaParam) ps.setString(idx++, schemaPattern);
        ps.setString(idx++, nullPatternToWildcard(typeNamePattern));
        if (types != null) {
            for (int t : types) {
                ps.setInt(idx++, t);
            }
        }
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection;
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
        return true;
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws SQLException {
        return false;
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
            throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
                                   String attributeNamePattern) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) throws SQLException {
        return false;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return 1; // or extract from version
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return 0;
    }

    @Override
    public int getJDBCMajorVersion() throws SQLException {
        return 1; // adjust to your JDBC compliance level
    }

    @Override
    public int getJDBCMinorVersion() throws SQLException {
        return 0;
    }

    @Override
    public int getSQLStateType() throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean locatorsUpdateCopy() throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean supportsStatementPooling() throws SQLException {
        return false;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
            throws SQLException {
        StringBuilder sb = new StringBuilder(512);
        sb.append("SELECT ");
        sb.append("NULL AS 'FUNCTION_CAT', ");
        sb.append("function_name AS 'FUNCTION_NAME', ");
        sb.append("schema_name AS 'FUNCTION_SCHEM', ");
        sb.append("description AS 'REMARKS', ");
        sb.append("CASE function_type ");
        sb.append("WHEN 'table' THEN ").append(DatabaseMetaData.functionReturnsTable).append(" ");
        sb.append("WHEN 'table_macro' THEN ").append(DatabaseMetaData.functionReturnsTable).append(" ");
        sb.append("ELSE ").append(DatabaseMetaData.functionNoTable).append(" ");
        sb.append("END AS 'FUNCTION_TYPE' ");
        sb.append("FROM duckdb_functions() WHERE TRUE ");
        boolean hasCatalogParam = appendEqualsQual(sb, "database_name", catalog);
        boolean hasSchemaParam = appendLikeQual(sb, "schema_name", schemaPattern);
        sb.append("AND function_name LIKE ? ESCAPE '\\' ");
        sb.append("ORDER BY \"FUNCTION_CAT\", \"FUNCTION_SCHEM\", \"FUNCTION_NAME\"");

        PreparedStatement ps = connection.prepareStatement(sb.toString());
        int idx = 1;
        if (hasCatalogParam) ps.setString(idx++, catalog);
        if (hasSchemaParam) ps.setString(idx++, schemaPattern);
        ps.setString(idx++, nullPatternToWildcard(functionNamePattern));
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern,
                                        String columnNamePattern) throws SQLException {
        Statement stmt = connection.createStatement();
        stmt.closeOnCompletion();
        return stmt.executeQuery("SELECT NULL WHERE FALSE");
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern,
                                      String columnNamePattern) throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws SQLException {
        throw new SQLException("Not supported");
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
            throws SQLException {
        Statement stmt = connection.createStatement();
        stmt.closeOnCompletion();
        return stmt.executeQuery("SELECT NULL WHERE FALSE");
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern,
                                         String columnNamePattern) throws SQLException {
        Statement stmt = connection.createStatement();
        stmt.closeOnCompletion();
        return stmt.executeQuery("SELECT NULL WHERE FALSE");
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