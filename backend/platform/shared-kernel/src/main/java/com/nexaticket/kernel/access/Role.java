// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.access;

/**
 * Vai trò theo architecture-v2/services.md §"Mô hình vai trò".
 *
 * <p>Nằm ở shared-kernel chứ không ở starter-security vì đây là <b>từ vựng nghiệp vụ dùng chung</b>
 * (ubiquitous language), không phải cơ chế bảo mật. Nhờ vậy domain layer dùng được mà không phải
 * phụ thuộc vào tầng hạ tầng.
 */
public enum Role {
    /** Nền tảng: tạo/khoá tổ chức, toàn quyền trên tiền (ADR-1010). */
    SUPER_ADMIN,
    ORG_OWNER,
    ORG_ADMIN,
    EVENT_MANAGER,
    CHECKIN_STAFF,
    CUSTOMER;

    /** Vai trò gắn với một tổ chức cụ thể. */
    public boolean isOrganizationScoped() {
        return this != SUPER_ADMIN && this != CUSTOMER;
    }

    public boolean isAtLeastOrgAdmin() {
        return this == ORG_OWNER || this == ORG_ADMIN;
    }

    public boolean canManageCatalog() {
        return this == ORG_OWNER || this == ORG_ADMIN || this == EVENT_MANAGER;
    }

    public boolean canCheckIn() {
        return isOrganizationScoped();
    }
}
