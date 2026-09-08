// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web.error;

/**
 * Mã lỗi là hợp đồng ổn định mà frontend dựa vào để chọn cách hiển thị.
 *
 * <p>{@code detail} trong response là tiếng Anh cho log; frontend tự dịch sang tiếng Việt theo bảng
 * trong {@code packages/ui/errors.ts}. Không bao giờ đổi hoặc xoá một mã đã phát hành.
 */
public interface ErrorCode {
    String code();

    int httpStatus();

    /** Mã dùng chung ở mọi service. Mã theo nghiệp vụ nằm trong service tương ứng. */
    enum Common implements ErrorCode {
        VALIDATION_FAILED(400),
        IDEMPOTENCY_KEY_REQUIRED(400),
        IDEMPOTENCY_KEY_REUSED(409),
        REQUEST_IN_PROGRESS(409),
        UNAUTHENTICATED(401),
        FORBIDDEN(403),
        NOT_FOUND(404),
        CONFLICT(409),
        RATE_LIMITED(429),
        INTERNAL_ERROR(500),
        UPSTREAM_UNAVAILABLE(503);

        private final int status;

        Common(int status) {
            this.status = status;
        }

        @Override
        public String code() {
            return name();
        }

        @Override
        public int httpStatus() {
            return status;
        }
    }
}
