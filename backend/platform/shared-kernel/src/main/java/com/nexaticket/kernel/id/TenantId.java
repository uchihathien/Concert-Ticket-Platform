// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Định danh tổ chức (tenant).
 *
 * <p>Bọc UUID có chủ đích: {@code reserve(orderId, sessionSeatId)} gọi nhầm thứ tự thì trình biên
 * dịch chặn, còn nếu cả hai đều là {@code UUID} thì lỗi chỉ lộ ra lúc chạy.
 */
public record TenantId(UUID value) {
    public TenantId {
        Objects.requireNonNull(value, "TenantId không được null");
    }

    public static TenantId of(UUID value) {
        return new TenantId(value);
    }

    public static TenantId parse(String value) {
        return new TenantId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
