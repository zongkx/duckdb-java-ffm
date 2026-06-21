package io.github.zongkx.ffm;

import org.duckdb.ffi.duckdb_h;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class DuckDBNative {

    // --- 跨平台 C 类型兼容性定义 ---
    public static final AddressLayout C_POINTER = ValueLayout.ADDRESS;
    public static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfByte C_INT8 = ValueLayout.JAVA_BYTE;
    public static final ValueLayout.OfShort C_INT16 = ValueLayout.JAVA_SHORT;
    public static final ValueLayout.OfInt C_INT32 = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfLong C_INT64 = ValueLayout.JAVA_LONG;
    public static final ValueLayout.OfFloat C_FLOAT = ValueLayout.JAVA_FLOAT;
    public static final ValueLayout.OfDouble C_DOUBLE = ValueLayout.JAVA_DOUBLE;
    
    // 动态适配 Linux (8字节) 和 Windows (4字节) 的 C long
    public static final ValueLayout.OfLong C_LONG = (Linker.nativeLinker().canonicalLayouts().get("long") instanceof ValueLayout.OfLong dl) 
            ? dl : ValueLayout.JAVA_LONG;
            
    // DuckDB 的 idx_t 永远是 8 字节 (uint64_t)
    public static final ValueLayout.OfLong C_IDX = ValueLayout.JAVA_LONG; 

    // 假设你的动态库查找器已经在外层定义好
    public static final SymbolLookup SYMBOL_LOOKUP = SymbolLookup.libraryLookup(java.nio.file.Path.of(System.mapLibraryName("duckdb")), Arena.global());

    // ==========================================
    // 1. 数据库与连接生命周期管理 (Database & Connection)
    // ==========================================

    public static class duckdb_open {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_open");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_close {
        public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_close");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_connect {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_connect");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_disconnect {
        public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_disconnect");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    // ==========================================
    // 2. 传统查询与基本元数据 API (Basic Query & Metadata)
    // ==========================================

    public static class duckdb_query {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_query");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_destroy_result {
        public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_destroy_result");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_column_count {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_IDX, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_column_count");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_row_count {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_IDX, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_row_count");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_rows_changed {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_IDX, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_rows_changed");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_column_name {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_POINTER, C_POINTER, C_IDX);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_column_name");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_column_type {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_IDX); // 返回 duckdb_type (int)
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_column_type");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_result_error {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_POINTER, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_result_error");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    // ==========================================
    // 3. 现代高性能列式数据块读取 API (Data Chunk & Vector)
    // ==========================================

    public static class duckdb_result_chunk_count {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_IDX, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_result_chunk_count");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_result_get_chunk {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_POINTER, C_POINTER, C_IDX); // 返回 duckdb_data_chunk 指针
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_result_get_chunk");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_destroy_data_chunk {
        public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_destroy_data_chunk");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_data_chunk_get_size {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_IDX, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_data_chunk_get_size");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_data_chunk_get_vector {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_POINTER, C_POINTER, C_IDX); // 返回 duckdb_vector 指针
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_data_chunk_get_vector");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_vector_get_data {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_POINTER, C_POINTER); // 返回原生数组的纯内存首地址
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_vector_get_data");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_vector_get_validity {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_POINTER, C_POINTER); // 返回 Null 掩码位图指针
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_vector_get_validity");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_validity_row_is_valid {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_IDX); // 判断某一行数据是否非 Null
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_validity_row_is_valid");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    // ==========================================
    // 4. 预编译 SQL 与参数绑定 API (Prepared Statements)
    // ==========================================

    public static class duckdb_prepare {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_prepare");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_destroy_prepare {
        public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_destroy_prepare");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_prepare_error {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_POINTER, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_prepare_error");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_nparams {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_IDX, C_POINTER); // 获取参数占位符个数
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_nparams");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_bind_boolean {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_IDX, C_INT8);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_bind_boolean");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_bind_int32 {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_IDX, C_INT32);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_bind_int32");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_bind_int64 {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_IDX, C_INT64);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_bind_int64");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_bind_double {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_IDX, C_DOUBLE);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_bind_double");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_bind_varchar {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_IDX, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_bind_varchar");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_bind_null {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_IDX);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_bind_null");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_execute_prepared {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_execute_prepared");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    // ==========================================
    // 5. 传统平铺型单元值读取 API (仅用于轻量级 Fallback 或测试)
    // ==========================================

    public static class duckdb_value_varchar {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_POINTER, C_POINTER, C_IDX, C_IDX);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_value_varchar");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_value_is_null {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_IDX, C_IDX);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_value_is_null");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    
    // 你刚才提到的缓存管理 API（按需保留）
    public static class duckdb_get_or_create_from_cache {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_get_or_create_from_cache");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }


    // ==========================================
    // 内存释放工具 API (Memory Management)
    // ==========================================
    public static class duckdb_free {
        // C 原型: void duckdb_free(void *ptr);
        public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(C_POINTER);
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_free");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }

    public static class duckdb_clear_bindings {
        public static final FunctionDescriptor DESC  = FunctionDescriptor.of(duckdb_h.C_INT, duckdb_h.C_POINTER);;
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("duckdb_clear_bindings");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);


    }
}