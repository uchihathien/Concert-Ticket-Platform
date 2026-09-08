// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/**
 * Khách chọn vị trí hay chỉ chọn số lượng.
 *
 * <p>Một khu vực không thể vừa ngồi vừa đứng; muốn vậy chủ địa điểm chia thành hai khu vực. Ràng
 * buộc đó giữ cho mỗi đơn vị tồn kho chỉ có đúng một kiểu, và nhờ vậy Inventory không cần đường
 * code thứ hai cho bất biến chống oversell (ADR-1012).
 */
public enum AdmissionType {
    SEATED,
    STANDING
}
