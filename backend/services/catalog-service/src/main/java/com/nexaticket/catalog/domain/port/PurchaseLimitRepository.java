// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import com.nexaticket.catalog.domain.model.PurchaseLimits;
import java.util.UUID;

/** Hai tầng trần cấu hình được; tầng thứ ba nằm ở {@code event_sessions} (ADR-1014 §1). */
public interface PurchaseLimitRepository {

    /** Trần cứng của nền tảng. Luôn có đủ giá trị — migration tạo sẵn một hàng. */
    PurchaseLimits platformCeiling();

    void savePlatformCeiling(PurchaseLimits limits, UUID updatedBy);

    /** Mặc định của tổ chức; các trường null nghĩa là kế thừa nền tảng. */
    PurchaseLimits organizationDefaults(UUID organizationId);

    void saveOrganizationDefaults(UUID organizationId, PurchaseLimits limits);
}
