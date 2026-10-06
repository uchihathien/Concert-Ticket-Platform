// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.model;

/**
 * Kiểu vào cửa của một đơn vị tồn kho.
 *
 * <p>Một zone không thể vừa ngồi vừa đứng; muốn vậy thì chủ địa điểm chia thành hai zone
 * (ADR-1012). Nhờ ràng buộc đó mà mỗi đơn vị tồn kho chỉ có đúng một kiểu.
 */
public enum AdmissionType {
    /** Khách chọn đích danh vị trí. Hiện trên sơ đồ chỗ. */
    SEATED,

    /** Khách chỉ chọn số lượng. Là đơn vị ảo, không hiện trên sơ đồ chỗ. */
    STANDING
}
