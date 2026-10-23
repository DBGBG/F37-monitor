package com.moxa.monitor.common;

import java.util.List;

/**
 * 统一分页载荷, 作为 Result.data 返回.
 * 字段名经全局 SNAKE_CASE 后为 {total,page,page_size,items}, 与迁移前的 Python 响应一致.
 */
public record PageResult<T>(long total, int page, int pageSize, List<T> items) {

    public static <T> PageResult<T> of(long total, int page, int pageSize, List<T> items) {
        return new PageResult<>(total, page, pageSize, items == null ? List.of() : items);
    }

    public static <T> PageResult<T> empty(int page, int pageSize) {
        return new PageResult<>(0L, page, pageSize, List.of());
    }
}
