// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.util.UUID;

/**
 * Một khu cố định trong khung concert.
 *
 * <p>Hình dạng giống hệt {@link VenueZone} — cùng mô hình chữ nhật {@code rowCount × seatsPerRow},
 * cùng luật cho khu đứng. Đó là điều kiện để {@link #materialize(UUID)} không phải dịch gì cả:
 * khung là một địa điểm chưa có chủ, và áp khung là chép nó sang một chủ.
 *
 * @param suggestedPriceVnd giá gợi ý, {@code null} nghĩa là khung không gợi ý. Giá KHÔNG bị áp
 *     cứng theo khung: khu vực là kết cấu (nền tảng quyết định), giá là quyết định thương mại của
 *     tổ chức (ADR-1010).
 */
public record TemplateZone(
        UUID id,
        UUID templateId,
        String zoneCode,
        String name,
        AdmissionKind kind,
        Integer rowCount,
        Integer seatsPerRow,
        Integer capacity,
        int sortOrder,
        Long suggestedPriceVnd) {

    public TemplateZone {
        if (zoneCode == null || zoneCode.isBlank()) {
            throw new IllegalArgumentException("Khu của khung phải có mã");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Khu của khung phải có tên");
        }
        // Cùng luật với VenueZone, viết lại chứ không gọi sang: hai kiểu khác nhau, và một
        // TemplateZone chưa có venueId để dựng VenueZone tạm mà kiểm.
        if (kind == AdmissionKind.SEATED) {
            if (rowCount == null || seatsPerRow == null || rowCount <= 0 || seatsPerRow <= 0) {
                throw new IllegalArgumentException("Khu ngồi phải có số hàng và số ghế mỗi hàng > 0");
            }
            capacity = null;
        } else {
            if (capacity == null || capacity <= 0) {
                throw new IllegalArgumentException("Khu đứng phải có sức chứa > 0");
            }
            rowCount = null;
            seatsPerRow = null;
        }
        if (suggestedPriceVnd != null && suggestedPriceVnd < 0) {
            throw new IllegalArgumentException("Giá gợi ý không được âm");
        }
    }

    public static TemplateZone create(
            UUID templateId,
            String zoneCode,
            String name,
            AdmissionKind kind,
            Integer rowCount,
            Integer seatsPerRow,
            Integer capacity,
            int sortOrder,
            Long suggestedPriceVnd) {
        return new TemplateZone(
                UUID.randomUUID(),
                templateId,
                zoneCode,
                name,
                kind,
                rowCount,
                seatsPerRow,
                capacity,
                sortOrder,
                suggestedPriceVnd);
    }

    /** Số chỗ bán được của khu — cùng công thức với {@link VenueZone#seatCount()}. */
    public int seatCount() {
        return kind == AdmissionKind.SEATED ? rowCount * seatsPerRow : capacity;
    }

    /**
     * Chép khu này thành khu thật của một địa điểm.
     *
     * <p><b>Chép chứ không tham chiếu.</b> Sự kiện đã tạo phải giữ nguyên sơ đồ của nó kể cả khi
     * nền tảng sửa hoặc lưu trữ khung sau đó — cùng nguyên tắc snapshot mà hệ thống áp cho giá và
     * cho tài khoản ngân hàng. {@code sourceTemplateZoneId} giữ lại dấu vết để biết khu này đến từ
     * đâu, và để chặn tổ chức sửa một khu vốn là kết cấu cố định.
     */
    public VenueZone materialize(UUID venueId) {
        return new VenueZone(
                UUID.randomUUID(), venueId, zoneCode, name, kind, rowCount, seatsPerRow, capacity, sortOrder, id);
    }
}
