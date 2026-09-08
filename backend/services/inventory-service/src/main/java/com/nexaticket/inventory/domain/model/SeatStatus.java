// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.model;

/**
 * Vòng đời một đơn vị tồn kho.
 *
 * <pre>
 *   AVAILABLE ──giữ chỗ──▶ HELD ──đặt hàng──▶ RESERVED ──thanh toán──▶ SOLD
 *       ▲                   │                    │                      │
 *       └───────────────────┴────────────────────┴──────────────────────┘
 *          hết hạn giữ chỗ · đơn hết hạn · huỷ đơn · hoàn tiền
 * </pre>
 *
 * <p>Bốn đường quay về {@code AVAILABLE} đều phải xoá {@code holder_user_id}, nếu không khách bị
 * khoá oan hạn mức (ADR-1014). Database có CHECK ép điều đó nên quên là ghi không chạy được.
 */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    RESERVED,
    SOLD,

    /** Tổ chức khoá tay: ghế kỹ thuật, ghế mời, ghế hỏng. Không bao giờ vào luồng bán. */
    BLOCKED
}
