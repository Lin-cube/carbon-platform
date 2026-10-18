package com.carbon.platform.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果。
 */
@Data
public class PageResult<T> implements Serializable {

    private List<T> records;
    private long total;
    private long pageNum;
    private long pageSize;
    private long pages;

    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        PageResult<T> r = new PageResult<>();
        r.setRecords(records);
        r.setTotal(total);
        r.setPageNum(pageNum);
        r.setPageSize(pageSize);
        r.setPages(pageSize > 0 ? (total + pageSize - 1) / pageSize : 0);
        return r;
    }
}
