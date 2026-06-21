package io.github.zongkx;

import io.github.zongkx.ffm.DuckDBResultSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DuckdbJdbcResultSetTest {

    @Test
    void testWasNull_AfterStringRead() throws Exception {
        DuckDBResultSet mockNative = mock(DuckDBResultSet.class);
        // 模拟第一列为 null
        when(mockNative.getString(0)).thenReturn(null);
        
        DuckdbJdbcResultSet jdbcRs = new DuckdbJdbcResultSet(mockNative);
        
        String val = jdbcRs.getString(1);
        
        assertNull(val);
        assertTrue(jdbcRs.wasNull(), "wasNull() 应该返回 true");
    }

    @Test
    void testIntParsing_ValidData() throws Exception {
        DuckDBResultSet mockNative = mock(DuckDBResultSet.class);
        when(mockNative.getString(0)).thenReturn("123");
        
        DuckdbJdbcResultSet jdbcRs = new DuckdbJdbcResultSet(mockNative);
        
        assertEquals(123, jdbcRs.getInt(1));
        assertFalse(jdbcRs.wasNull());
    }
}