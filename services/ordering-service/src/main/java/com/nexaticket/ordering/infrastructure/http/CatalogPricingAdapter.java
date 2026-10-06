// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.infrastructure.http;

import com.nexaticket.ordering.domain.port.PricingPort;
import com.nexaticket.ordering.domain.port.RemoteCallException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang catalog-service.
 *
 * <p>Phân biệt "Catalog trả lời dứt khoát" với "không gọi được Catalog" là toàn bộ giá trị của lớp
 * này, và nó phải đọc <b>mã lỗi trong body</b> chứ không chỉ nhìn nhóm mã HTTP.
 *
 * <p>Trước đây mọi 4xx đều bị coi là "mã khuyến mãi không hợp lệ". Hệ quả là một cái 404 vì gọi
 * nhầm đường dẫn — hay vì endpoint chưa được viết — hiện ra với khách thành thông báo về mã giảm
 * giá mà họ chưa từng nhập, còn người đi sửa thì đi soi module khuyến mãi hoàn toàn không liên quan.
 */
@Component
public class CatalogPricingAdapter implements PricingPort {

    private static final String SERVICE = "catalog-service";

    /** Hợp đồng với catalog-service: đúng mã này mới là "mã khuyến mãi không dùng được". */
    private static final String PROMOTION_INVALID = "PROMOTION_INVALID";

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
                            // CHỈ đúng một mã lỗi là câu trả lời nghiệp vụ. Mọi 4xx khác — 404 vì
                            // sai đường dẫn, 401 vì thiếu xác thực nội bộ — là hỏng hạ tầng, và
                            // phải đi đường bù trừ + "thử lại", không đổ cho khách.
                            if (PROMOTION_INVALID.equals(errorCodeOf(clientResponse))) {
                                throw new PromotionInvalidException("Promotion code is not valid for this session");
                            }
                            throw new RemoteCallException(SERVICE, "Catalog trả " + status, null);
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

    /** Mã lỗi nghiệp vụ nằm trong body RFC 7807; đọc không ra thì coi như lỗi hạ tầng. */
    private static String errorCodeOf(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            Map<?, ?> body = response.bodyTo(Map.class);
            Object code = body == null ? null : body.get("code");
            return code == null ? "" : code.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    record PricingResponse(int commissionBps, long discountVnd) {}
}
