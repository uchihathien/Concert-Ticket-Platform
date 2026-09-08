// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.model;

/** ADR-1010 §2: máy trạng thái KYC đã gỡ; superadmin thẩm định trước khi tạo. */
public enum OrganizationStatus {
    ACTIVE,
    SUSPENDED
}
