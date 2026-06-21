package io.github.zongkx.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public class DuckDBResultSet implements AutoCloseable {

    private final MemorySegment resultStruct;  // 承载整个 duckdb_result 结构体实体的内存
    private final Arena arena;                  // 内部私有内存域（若不拥有结构体则为 null）
    private final boolean ownsStruct;           // 核心修复：判定是否拥有结构体的销毁权

    private long totalRows = 0;
    private long totalCols = 0;
    private long currentRow = -1; // -1 代表游标还未开始移动
    private boolean isClosed = false;

    public MemorySegment getHandle() {
        return this.resultStruct;
    }

    public DuckDBResultSet(DuckDBConnection conn, String sql) {
        this.arena = Arena.ofConfined(); // 结果集内部私有的、单线程闭环内存域
        this.ownsStruct = true;
        try {
            // 使用 duckdb-ffm 库提供的正确 struct 大小
            this.resultStruct = arena.allocate(128);
            MemorySegment cSql = arena.allocateFrom(sql);

            // 调用 C API 执行查询
            int rc = (int) DuckDBNative.duckdb_query.HANDLE.invokeExact(conn.getHandle(), cSql, resultStruct);
            if (rc != 0) {
                MemorySegment errorMsg = (MemorySegment) DuckDBNative.duckdb_result_error.HANDLE.invokeExact(resultStruct);
                // 使用当前的全局/外部可控生存期进行重解释，防止直接崩掉
                String err = errorMsg.reinterpret(Long.MAX_VALUE).getString(0);
                throw new RuntimeException("DuckDB 查询执行失败: " + err);
            }

            // 提取元数据
            this.totalRows = (long) DuckDBNative.duckdb_row_count.HANDLE.invokeExact(resultStruct);
            this.totalCols = (long) DuckDBNative.duckdb_column_count.HANDLE.invokeExact(resultStruct);

        } catch (Throwable t) {
            arena.close(); // 发生异常立即断后
            throw new RuntimeException(t);
        }
    }

    public DuckDBResultSet(MemorySegment preAllocatedResultStruct) {
        this.arena = null;
        this.resultStruct = preAllocatedResultStruct;
        this.ownsStruct = false; // 标记不拥有结构体释放权
        try {
            this.totalRows = (long) DuckDBNative.duckdb_row_count.HANDLE.invokeExact(resultStruct);
            this.totalCols = (long) DuckDBNative.duckdb_column_count.HANDLE.invokeExact(resultStruct);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public MemorySegment getStruct() {
        return resultStruct;
    }

    public boolean next() {
        if (isClosed) return false;
        if (currentRow + 1 < totalRows) {
            currentRow++;
            return true;
        }
        return false;
    }

    public long getTotalColumns() {
        return totalCols;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public String getString(long colIdx) {
        if (isClosed || currentRow < 0 || currentRow >= totalRows) {
            throw new IllegalStateException("游标不在有效的数据行上");
        }
        if (colIdx < 0 || colIdx >= totalCols) {
            throw new IndexOutOfBoundsException("列索引超出界限: " + colIdx);
        }

        try {
            // 调用官方推荐的快捷转换 C 函数 duckdb_value_varchar
            MemorySegment cStr = (MemorySegment) DuckDBNative.duckdb_value_varchar.HANDLE.invokeExact(resultStruct, colIdx, currentRow);

            if (cStr.equals(MemorySegment.NULL)) {
                return null;
            }

            // 修复：利用当前调用作用域（如全局或单次边界）安全解构，防止潜在的 JVM 越界 Crash
            String val = cStr.reinterpret(Long.MAX_VALUE).getString(0);

            // 极其关键：释放由 duckdb_value_varchar 在 C 堆上动态分配的字符串内存
            DuckDBNative.duckdb_free.HANDLE.invokeExact(cStr);

            return val;
        } catch (Throwable t) {
            throw new RuntimeException("提取列数据失败，列索引: " + colIdx, t);
        }
    }


    public String getColumnName(long colIdx) {
        if (colIdx < 0 || colIdx >= totalCols) throw new IndexOutOfBoundsException();
        try {
            MemorySegment cName = (MemorySegment) DuckDBNative.duckdb_column_name.HANDLE.invokeExact(resultStruct, colIdx);
            return cName.reinterpret(Long.MAX_VALUE).getString(0);
            // 注意：duckdb_column_name 返回的指针属于结果集内部，不需要手动释放
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }


    @Override
    public void close() {
        if (isClosed) return;
        try {
            if (ownsStruct && resultStruct != null && !resultStruct.equals(MemorySegment.NULL)) {
                DuckDBNative.duckdb_destroy_result.HANDLE.invokeExact(resultStruct);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (arena != null) {
                arena.close();
            }
            isClosed = true;
        }
    }
}