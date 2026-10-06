// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.nexaticket.identity.domain.model.Organization;
import java.util.List;

/**
 * Hình chiếu đọc của tổ chức.
 *
 * <p>Tồn tại để aggregate không rò ra khỏi tầng application: controller nhận DTO này, không nhận
 * {@link Organization} (tactical-ddd.md §7). Nhờ vậy đổi mô hình domain không làm vỡ hợp đồng HTTP.
 */
public record OrganizationView(String id, String slug, String name, String status, List<MemberView> members) {

    public static OrganizationView from(Organization organization) {
        return new OrganizationView(
                organization.id().toString(),
                organization.slug().value(),
                organization.name(),
                organization.status().name(),
                organization.members().stream().map(MemberView::from).toList());
    }

    public int memberCount() {
        return members.size();
    }
}
