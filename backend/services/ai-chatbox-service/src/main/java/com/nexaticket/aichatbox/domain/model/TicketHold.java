// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Một lần giữ chỗ vừa đặt ở inventory-service.
 *
 * @param expiresAt hết hạn thì chỗ tự nhả — inventory quyết định (mặc định 10 phút), agent chỉ
 *     đọc lại để nói cho khách
 * @param quantity số chỗ thật sự giữ được, có thể ít hơn số xin nếu hệ thống chọn hộ
 */
public record TicketHold(UUID holdId, Instant expiresAt, int quantity) {}
