// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của catalog. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum CatalogErrorCode implements ErrorCode {
    VENUE_NOT_FOUND(404),
    ZONE_NOT_FOUND(404),
    EVENT_NOT_FOUND(404),
    SESSION_NOT_FOUND(404),
    SLUG_ALREADY_TAKEN(409),
    ZONE_ALREADY_PRICED(409),
    /** Còn vướng mắc chưa gỡ; danh sách nằm ở {@code meta.blockers}. */
    PUBLISH_BLOCKED(409),
    EVENT_NOT_PUBLISHED(409),
    INVALID_EVENT_STATE(409);

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
