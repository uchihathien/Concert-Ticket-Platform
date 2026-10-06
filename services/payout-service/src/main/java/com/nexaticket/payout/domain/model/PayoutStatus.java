// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.domain.model;

/**
 * Vòng đời một lô chi trả.
 *
 * <pre>
 *   PENDING_APPROVAL ──duyệt──▶ APPROVED ──xác nhận đã chuyển──▶ COMPLETED
 *          │
 *          └──từ chối──▶ REJECTED
 * </pre>
 *
 * <p>Không có đường từ {@code COMPLETED} quay lại: tiền đã rời tài khoản ký quỹ. Sửa sai phải là
 * một bút toán đảo trong sổ cái, không phải sửa trạng thái ở đây.
 */
public enum PayoutStatus {
    PENDING_APPROVAL,
    APPROVED,
    COMPLETED,
    REJECTED
}
