// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của catalog. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum CatalogErrorCode implements ErrorCode {
    EVENT_NOT_FOUND(404),
    SESSION_NOT_FOUND(404),

    /** Preflight publish không qua; chi tiết từng mục nằm trong meta. */
    PUBLISH_PREFLIGHT_FAILED(422),

    /** Tổ chức đặt trần vượt trần cứng của nền tảng — chặn ngay lúc lưu (ADR-1014 §1). */
    LIMIT_EXCEEDS_PLATFORM_CEILING(400),

    PROMOTION_INVALID(400);

    private final int status;

    CatalogErrorCode(int status) {
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
