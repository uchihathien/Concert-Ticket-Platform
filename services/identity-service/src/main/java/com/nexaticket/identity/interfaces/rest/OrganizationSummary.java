// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.query.OrganizationView;

/**
 * Hình dạng response HTTP.
 *
 * <p>Tách khỏi {@link OrganizationView} có chủ đích: view là DTO nội bộ của tầng application, còn
 * record này là <b>hợp đồng công khai</b>. Đổi view không được kéo theo phá vỡ API.
 */
public record OrganizationSummary(String id, String slug, String name, String status, int memberCount) {

    public static OrganizationSummary from(OrganizationView view) {
        return new OrganizationSummary(view.id(), view.slug(), view.name(), view.status(), view.memberCount());
    }
}
