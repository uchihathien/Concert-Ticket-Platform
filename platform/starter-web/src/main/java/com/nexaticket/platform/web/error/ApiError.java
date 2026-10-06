// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** Hình dạng lỗi duy nhất cho toàn bộ API (RFC 7807 + trường {@code code}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String type,
        String title,
        int status,
        String code,
        String detail,
        String correlationId,
        Map<String, Object> meta) {

    private static final String TYPE_BASE = "https://nexaticket.vn/errors/";

    public static ApiError of(ErrorCode errorCode, String detail, String correlationId, Map<String, Object> meta) {
        String slug = errorCode.code().toLowerCase().replace('_', '-');
        return new ApiError(
                TYPE_BASE + slug,
                slug.replace('-', ' '),
                errorCode.httpStatus(),
                errorCode.code(),
                detail,
                correlationId,
                meta == null || meta.isEmpty() ? null : meta);
    }
}
