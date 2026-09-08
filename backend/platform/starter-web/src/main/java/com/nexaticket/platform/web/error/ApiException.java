// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.error;

import java.util.Map;

/** Ngoại lệ nghiệp vụ mang theo mã lỗi ổn định và dữ liệu phụ cho frontend. */
public class ApiException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> meta;

    public ApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of());
    }

    public ApiException(ErrorCode errorCode, String detail, Map<String, Object> meta) {
        super(detail);
        this.errorCode = errorCode;
        this.meta = Map.copyOf(meta);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> meta() {
        return meta;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.Common.NOT_FOUND, what + " not found");
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(ErrorCode.Common.FORBIDDEN, detail);
    }
}
