// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.http;

import com.nexaticket.catalog.domain.port.SalesReportPort;
import com.nexaticket.catalog.domain.port.UpstreamUnavailableException;
import com.nexaticket.platform.security.InternalApiProperties;
import com.nexaticket.platform.security.tenant.InternalApiFilter;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang analytics-service.
 *
 * <p>Cổng này cố ý hẹp: chỉ số vé bán và tiền đã bán. Analytics không có endpoint nào trả hoa hồng
 * hay số dư, và nếu sau này có thì lớp này cũng không gọi tới — ranh giới ADR-1010 được giữ bằng
 * hình dạng của kiểu dữ liệu, không bằng một dòng ghi chú.
 *
 * <p>Không có nhánh 404: analytics trả danh sách rỗng cho một sự kiện chưa bán được vé nào. Đó là
 * hợp đồng đúng cho một read model cộng dồn — "chưa có hàng nào" không phải là "không tìm thấy".
 */
@Component
public class AnalyticsSalesAdapter implements SalesReportPort {

    private static final String SERVICE = "analytics-service";

    private final RestClient client;
    private final String internalToken;

    public AnalyticsSalesAdapter(@Qualifier("analyticsClient") RestClient client, InternalApiProperties internal) {
        this.client = client;
        this.internalToken = internal.sharedSecret();
    }

    @Override
    public List<SessionSales> forEvent(UUID eventId) {
        return fetch("/internal/events/{id}/sales", eventId);
    }

    @Override
    public List<SessionSales> forOrganization(UUID organizationId) {
        return fetch("/internal/organizations/{id}/sales", organizationId);
    }

    private List<SessionSales> fetch(String uriTemplate, UUID id) {
        try {
            SalesResponse response = client.get()
                    .uri(uriTemplate, id)
                    .headers(headers -> {
                        if (internalToken != null && !internalToken.isBlank()) {
                            headers.set(InternalApiFilter.HEADER, internalToken);
                        }
                    })
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new UpstreamUnavailableException(SERVICE, "Analytics trả " + status, null);
                        }
                        return clientResponse.bodyTo(SalesResponse.class);
                    });
            if (response == null || response.sessions() == null) {
                return List.of();
            }
            return response.sessions().stream().map(SessionRow::toDomain).toList();
        } catch (UpstreamUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UpstreamUnavailableException(SERVICE, "Không gọi được analytics-service", e);
        }
    }

    /** Hình dạng JSON là hợp đồng với {@code InternalSalesController} của analytics-service. */
    record SalesResponse(List<SessionRow> sessions) {}

    record SessionRow(
            UUID eventSessionId,
            UUID eventId,
            int ticketsSold,
            long grossVnd,
            int ordersPaid,
            int ordersExpired,
            int ordersCancelled) {

        SessionSales toDomain() {
            return new SessionSales(
                    eventSessionId, eventId, ticketsSold, grossVnd, ordersPaid, ordersExpired, ordersCancelled);
        }
    }
}
