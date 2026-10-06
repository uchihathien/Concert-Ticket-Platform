// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/**
 * Vòng đời của một khung concert.
 *
 * <p>Ba trạng thái chứ không phải hai. {@code DRAFT} tồn tại vì một khung chưa khai khu nào là một
 * khung sinh ra sự kiện không bán được gì: tổ chức chọn nó, điền xong mọi thứ, rồi nhận
 * {@code VENUE_WITHOUT_ZONE} ở bước publish — ở màn hình của người không gây ra lỗi và không sửa
 * được nó.
 */
public enum TemplateStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED;

    /** Tổ chức chỉ thấy và chỉ áp được khung ACTIVE. */
    public boolean isUsable() {
        return this == ACTIVE;
    }
}
