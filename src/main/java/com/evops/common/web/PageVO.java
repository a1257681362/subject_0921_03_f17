package com.evops.common.web;

import java.util.Collections;
import java.util.List;

/**
 * 分页响应包装。
 */
public class PageVO<T> {
    private long page;
    private long size;
    private long total;
    private List<T> list;

    public PageVO() {
    }

    public PageVO(long page, long size, long total, List<T> list) {
        this.page = page;
        this.size = size;
        this.total = total;
        this.list = list;
    }

    public static <T> PageVO<T> empty(long page, long size) {
        return new PageVO<>(page, size, 0, Collections.emptyList());
    }

    public long getPage() {
        return page;
    }

    public long getSize() {
        return size;
    }

    public long getTotal() {
        return total;
    }

    public List<T> getList() {
        return list;
    }
}
