// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.model;

/**
 * Xin một số lượng vé đứng ở một zone.
 *
 * <p>Khác vé ngồi ở chỗ khách không chỉ đích danh đơn vị nào — nên không có gì để từ chối nhanh,
 * và đó chính là lý do vé đứng không đi qua Redis (ADR-1012).
 */
public record StandingRequest(String zoneCode, int quantity) {

    public StandingRequest {
        if (zoneCode == null || zoneCode.isBlank()) {
            throw new IllegalArgumentException("zoneCode không được rỗng");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity phải dương, nhận " + quantity);
        }
    }
}
