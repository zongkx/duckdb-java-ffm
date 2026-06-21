package io.github.zongkx;

import io.github.zongkx.ffm.DuckDBConnection;
import io.github.zongkx.ffm.DuckDBResultSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DuckDBJdbcStatementTest {

    private DuckDBJdbcConnection mockJdbcConn;
    private DuckDBConnection mockNativeConn;
    private DuckDBJdbcStatement statement;

    @BeforeEach
    void setUp() {
        mockJdbcConn = mock(DuckDBJdbcConnection.class);
        mockNativeConn = mock(DuckDBConnection.class);
        statement = new DuckDBJdbcStatement(mockJdbcConn, mockNativeConn);
    }

    @Test
    void testExecuteQuery_ShouldCallNotifyAndReturnResultSet() throws SQLException {
        // Mock 底层查询
        DuckDBResultSet mockNativeResult = mock(DuckDBResultSet.class);
        when(mockNativeConn.query("SELECT 1")).thenReturn(mockNativeResult);

        ResultSet rs = statement.executeQuery("SELECT 1");

        assertNotNull(rs);
        verify(mockJdbcConn, times(1)).notifyStatementExecution();
    }

    @Test
    void testClose_ShouldUntrackStatement() throws SQLException {
        statement.close();
        
        assertTrue(statement.isClosed());
        verify(mockJdbcConn, times(1)).untrackStatement(statement);
    }
}