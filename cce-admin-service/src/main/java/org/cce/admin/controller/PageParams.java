package org.cce.admin.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.cce.admin.controller.dto.CommonDtos.Pagination;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The {@code page} and {@code pageSize} query parameters of section 4.3, bound with {@code @Valid}.
 * {@code page} is 1-based, default 1, minimum 1. {@code pageSize} defaults to 20, from 1 to 100. A
 * value out of bounds, or not an integer, is a 400 {@code VALIDATION_ERROR}; it is never clamped.
 */
public record PageParams(@Min(1) Integer page, @Min(1) @Max(100) Integer pageSize) {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;

    public PageParams {
        page = page == null ? DEFAULT_PAGE : page;
        pageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }

    public PageRequest toPageRequest(Sort sort) {
        return PageRequest.of(page - 1, pageSize, sort);
    }

    /** The pagination block. {@code totalPages} is 0 when {@code totalItems} is 0. */
    public Pagination toPagination(long totalItems) {
        int totalPages = (int) ((totalItems + pageSize - 1) / pageSize);
        return new Pagination(page, pageSize, totalItems, totalPages);
    }
}
