// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

/**
 * Khu bán vé ngồi hay vé đứng.
 *
 * <p>Tồn tại vì inventory-service nhận hai danh sách khác nhau cho hai loại này và <b>không tự tra
 * loại hộ</b>: khai sai thì kết quả là "khu không đủ chỗ", một câu trả lời đúng về mặt kỹ thuật và
 * sai hoàn toàn với khách. Agent phải biết loại trước khi giữ chỗ, và nguồn của nó là sơ đồ khu của
 * catalog.
 */
public enum ZoneAdmission {
    SEATED,
    STANDING
}
