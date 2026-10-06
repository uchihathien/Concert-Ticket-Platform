// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/** Vòng đời sự kiện (docs/02-catalog-admin/state-machines.md). */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    UNPUBLISHED,
    CANCELLED;

    /** Khách chỉ thấy PUBLISHED. UNPUBLISHED là "đã từng bán rồi rút xuống", không phải bản nháp. */
    public boolean isVisibleToPublic() {
        return this == PUBLISHED;
    }

    public boolean canPublish() {
        return this == DRAFT || this == UNPUBLISHED;
    }
}
