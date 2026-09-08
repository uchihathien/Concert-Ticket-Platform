// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/**
 * Khu vực do ai định đoạt.
 *
 * <p>Đây là một trong hai chiều độc lập của mô hình khu vực (ADR-1012); chiều kia là
 * {@link AdmissionType}. Bốn tổ hợp phủ hết ba kiểu concert mà không cần khái niệm mới.
 */
public enum ZoneKind {

    /**
     * Chỗ áp cứng của địa điểm: khán đài bê tông, ghế bắt vít.
     *
     * <p>Tổ chức thuê chỗ <b>không sửa được</b> — họ chỉ được bật/tắt cả khu vực hoặc chặn từng
     * chỗ cho sự kiện của mình. Ghế ở đây là tài sản của địa điểm, không phải của sự kiện.
     */
    FIXED,

    /** Sàn trống, tổ chức tự bố trí theo từng sự kiện. */
    FLEXIBLE
}
