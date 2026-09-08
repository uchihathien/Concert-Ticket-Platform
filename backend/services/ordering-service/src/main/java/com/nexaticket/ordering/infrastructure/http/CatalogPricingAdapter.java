// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.infrastructure.http;

import com.nexaticket.ordering.domain.port.PricingPort;
import com.nexaticket.ordering.domain.port.RemoteCallException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Anti-Corruption Layer sang catalog-service. */
@Component
public class CatalogPricingAdapter implements PricingPort {

    private static final String SERVICE = "catalog-service";

    private final RestClient client;

    public CatalogPricingAdapter(@Qualifier("catalogClient") RestClient client) {
        this.client = client;
    }

    @Override
    public Pricing resolve(UUID eventSessionId, String promotionCode, long subtotalVnd) {
        try {
            PricingResponse response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/sessions/{id}/pricing")
                            .queryParam("promotionCode", promotionCode)
                            .queryParam("subtotalVnd", subtotalVnd)
                            .build(eventSessionId))
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (status.is4xxClientError()) {
                            throw new PromotionInvalidException("Promotion code is not valid for this session");
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new RemoteCallException(SERVICE, "Catalog trả " + status, null);
                        }
                        return clientResponse.bodyTo(PricingResponse.class);
                    });
            if (response == null) {
                throw new RemoteCallException(SERVICE, "Catalog trả body rỗng", null);
            }
            return new Pricing(response.commissionBps(), response.discountVnd());
        } catch (PromotionInvalidException | RemoteCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RemoteCallException(SERVICE, "Không gọi được catalog-service", e);
        }
    }

    record PricingResponse(int commissionBps, long discountVnd) {}
}
