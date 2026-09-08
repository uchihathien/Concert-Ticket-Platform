// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/** Địa điểm dùng chung của nền tảng, hay địa điểm riêng của một tổ chức. */
public enum VenueScope {

    /** Superadmin sở hữu; mọi tổ chức chọn được. Nhà hát Lớn, sân vận động... */
    PLATFORM,

    /** Tổ chức tự dựng, chỉ mình dùng. */
    ORGANIZATION
}
