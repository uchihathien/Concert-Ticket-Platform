// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.domain.port;

import java.util.UUID;

/** Đọc hồ sơ tổ chức từ identity-service. */
public interface OrganizationPort {

    Profile profileOf(UUID organizationId);

    /**
     * @param legalName tên pháp nhân, để đối chiếu với tên chủ tài khoản nhận tiền
     * @param active tổ chức có bị đình chỉ không
     */
    record Profile(UUID organizationId, String legalName, boolean active) {}
}
