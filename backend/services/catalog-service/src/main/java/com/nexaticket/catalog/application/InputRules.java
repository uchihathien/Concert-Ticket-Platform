// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Biên giữa dữ liệu người dùng gõ và các luật của domain.
 *
 * <p>Domain từ chối dữ liệu sai bằng {@link IllegalArgumentException} kèm câu giải thích nghiệp vụ.
 * Request chỉ kiểm hình dạng, nên mọi luật sâu hơn đi tới đó; nếu không dịch ở đây, người dùng gõ
 * sai mã khu nhận 500 "Unexpected error" như một sự cố hệ thống.
 */
public final class InputRules {

    private InputRules() {}

    public static <T> T validated(Supplier<T> construct) {
        try {
            return construct.get();
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, e.getMessage());
        }
    }

    /**
     * Cửa bán phải đóng sau khi mở. {@code ck_sales_window} ở database cũng chặn, nhưng bằng
     * DataIntegrityViolation — tức 500. (Hợp lệ so với giờ diễn là việc của preflight lúc publish:
     * bản nháp dở dang được phép lưu.)
     */
    public static void requireSalesWindow(Instant open, Instant close) {
        if (open != null && close != null && !close.isAfter(open)) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, "Cửa bán phải đóng sau khi mở");
        }
    }
}
