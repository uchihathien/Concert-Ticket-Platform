// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.UUID;

/**
 * Tổ chức dùng một khu vực như thế nào cho suất diễn của mình.
 *
 * @param included khu vực này có được bán ở sự kiện này không
 * @param ticketTierId hạng vé áp cho cả khu vực; gán theo khu vực chứ không theo từng ghế, vì bắt
 *     tổ chức gán hạng vé cho 3.000 ghế là bắt họ bỏ cuộc
 * @param standingCapacity số vé đứng muốn bán; {@code null} nghĩa là lấy trọn sức chứa khu vực
 */
public record ZoneUsage(UUID zoneId, boolean included, UUID ticketTierId, Integer standingCapacity) {}
