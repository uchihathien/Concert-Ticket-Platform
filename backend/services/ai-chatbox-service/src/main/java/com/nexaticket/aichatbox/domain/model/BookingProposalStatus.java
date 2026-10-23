// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

/**
 * Vòng đời một đề xuất đặt vé — khớp CHECK {@code ck_proposal_status} của V0104.
 *
 * <p>Chỉ {@link #PROPOSED} còn chuyển được; ba trạng thái kia là đích, không quay lại.
 */
public enum BookingProposalStatus {
    /** Đang chờ khách bấm xác nhận; còn hiệu lực tới {@code expiresAt}. */
    PROPOSED,
    /** Khách đã xác nhận: chỗ đã giữ, đơn đã tạo. */
    CONFIRMED,
    /** Quá hạn mà không ai bấm — job hết hạn chuyển. */
    EXPIRED,
    /** Khách đổi ý, hoặc trợ lý thay bằng đề xuất mới cho cùng khu. */
    CANCELLED
}
