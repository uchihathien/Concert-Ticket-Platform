// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.util.UUID;

/**
 * Một lần phiếu đổi người giữ không qua "Nhận" — ghi vào {@code details.lastHandover}.
 *
 * <p>Để người đọc phiếu biết vì sao người phụ trách đổi và ai đổi. Không có nó, một phiếu tự dưng
 * về hàng đợi trông giống lỗi.
 *
 * @param action {@code RELEASE}, {@code REASSIGN} hoặc {@code AUTO_RELEASE} (reaper)
 * @param by người thao tác; {@code null} khi là reaper
 * @param from người giữ trước đó
 * @param to người giữ mới; {@code null} khi nhả về hàng đợi
 * @param reason lý do người thao tác ghi, có thể trống
 */
public record HandoverNote(String action, UUID by, UUID from, UUID to, String reason) {

    public static final String RELEASE = "RELEASE";
    public static final String REASSIGN = "REASSIGN";
    public static final String AUTO_RELEASE = "AUTO_RELEASE";
}
