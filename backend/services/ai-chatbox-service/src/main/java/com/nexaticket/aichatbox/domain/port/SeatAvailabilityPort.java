// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import java.util.Map;
import java.util.UUID;

/**
 * Số chỗ còn trống theo khu của một suất — CHỈ ĐỌC.
 *
 * <p>Port riêng, không gộp vào {@link InventoryClientPort}: bên dựng chip "khu nào còn chỗ" không được
 * phép nhìn thấy phương thức giữ chỗ (ADR-2001 — chỉ endpoint xác nhận mới giữ chỗ).
 */
public interface SeatAvailabilityPort {

    /**
     * @return số chỗ còn trống theo mã khu; map rỗng khi inventory không trả lời — chip vẫn hiện, chỉ
     *     thiếu con số. Không ném: thiếu "còn bao nhiêu chỗ" không phải lý do để khách không đặt được.
     */
    Map<String, Integer> availableByZone(UUID eventSessionId);
}
