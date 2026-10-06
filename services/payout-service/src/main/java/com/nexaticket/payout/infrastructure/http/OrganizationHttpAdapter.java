// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.infrastructure.http;

import com.nexaticket.payout.domain.port.OrganizationPort;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Đọc hồ sơ tổ chức từ identity-service.
 *
 * <p>Không đọc được thì coi tổ chức là <b>không hoạt động</b> và tên pháp nhân là rỗng — hai cổng
 * chặn sẽ đóng lại. Mặc định an toàn ở đây là "không chi trả": chi tiền dựa trên hồ sơ không xác
 * minh được là đúng thứ sáu cổng sinh ra để ngăn.
 */
@Component
public class OrganizationHttpAdapter implements OrganizationPort {

    private final RestClient client;

    public OrganizationHttpAdapter(@Qualifier("identityClient") RestClient client) {
        this.client = client;
    }

    @Override
    public Profile profileOf(UUID organizationId) {
        try {
            SummaryResponse response = client.get()
                    .uri("/internal/organizations/{id}/summary", organizationId)
                    .retrieve()
                    .body(SummaryResponse.class);
            if (response == null) {
                return new Profile(organizationId, "", false);
            }
            return new Profile(organizationId, response.legalName(), "ACTIVE".equals(response.status()));
        } catch (RuntimeException e) {
            return new Profile(organizationId, "", false);
        }
    }

    record SummaryResponse(String legalName, String status) {}
}
