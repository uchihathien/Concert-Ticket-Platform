// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lệnh tạo tổ chức — chỉ {@code SUPER_ADMIN} (ADR-1010).
 *
 * @param slug bỏ trống thì sinh từ {@code name}
 * @param ownerEmail người sẽ nhận lời mời làm {@code ORG_OWNER}
 */
public record CreateOrganization(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 64) String slug,
        @NotBlank @Email String ownerEmail,
        Profile profile) {

    public record Profile(
            String legalName, String taxCode, String representativeName, String contactPhone, String note) {}
}
