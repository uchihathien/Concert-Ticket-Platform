// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.platform.security.InternalApiProperties;
import java.io.IOException;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * Gắn {@code X-Internal-Token} vào MỌI request tới {@code /internal/**} đi qua {@link RestClient}
 * tự cấu hình của Spring Boot — một lần, cho mọi service, không phải nhớ ở từng adapter.
 *
 * <h2>Vì sao cần, và vì sao chỉ lộ ở production</h2>
 *
 * <p>{@link InternalApiFilter} ở phía nhận chỉ đòi header khi {@code nexaticket.internal.shared-secret}
 * được khai. Ở máy dev secret rỗng, nên một adapter quên gửi header vẫn chạy đúng — và đã có MƯỜI MỘT
 * adapter trong bảy service như vậy (ordering → inventory/catalog/payment, payment → ordering,
 * ticketing → identity/ordering, payout → ledger/identity, notification → identity, ai-chatbox →
 * identity/ordering). Ở production secret là bắt buộc (ProductionHardening chặn khởi động khi
 * thiếu), phía nhận trả <b>404 có chủ ý</b> cho request không có header, và phía gọi map 404 đó thành
 * lỗi nghiệp vụ của chính nó: ordering báo {@code SEAT_UNAVAILABLE "Inventory refused the
 * reservation"} cho một ghế vừa giữ xong. Toàn bộ chuỗi đơn → thanh toán → vé đứt, trong khi 22
 * container healthy và smoke test xanh — vì smoke test chỉ đi qua catalog, service duy nhất đã gắn
 * header bằng tay.
 *
 * <h2>Vì sao là RestClientCustomizer chứ không sửa từng adapter</h2>
 *
 * <p>Mười một chỗ sửa là mười một chỗ để quên lần sau. Customizer được Spring áp vào
 * {@code RestClient.Builder} tự cấu hình, nên điều kiện để một client được bảo vệ chỉ là "dựng từ
 * builder được inject" — đúng quy ước đã có cho {@code CorrelationPropagation} ở starter-web.
 * Client dựng bằng {@code RestClient.builder()} tĩnh KHÔNG đi qua đây. Đã rà: mọi adapter gọi nội bộ
 * trong mười hai service đều dựng từ builder được tiêm; chỗ tĩnh duy nhất là adapter Voyage của
 * ai-chatbox — gọi ra ngoài, và đúng ra phải nằm ngoài cơ chế này.
 *
 * <h2>Vì sao lọc theo đường dẫn</h2>
 *
 * <p>Cùng một builder có thể dựng client gọi ra ngoài (payOS, Keycloak). Gắn secret nội bộ vào
 * những request đó là đưa nó cho bên thứ ba. {@code /internal/} là quy ước của mọi endpoint nội bộ
 * trong hệ (xem {@link InternalApiFilter}), và không đối tác nào có đường dẫn như vậy.
 */
public final class InternalTokenPropagation implements ClientHttpRequestInterceptor, RestClientCustomizer {
    private final String token;

    public InternalTokenPropagation(InternalApiProperties internal) {
        this.token = internal.enforced() ? internal.sharedSecret() : null;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (token != null
                && request.getURI().getPath() != null
                && request.getURI().getPath().startsWith("/internal/")
                && !request.getHeaders().containsKey(InternalApiFilter.HEADER)) {
            request.getHeaders().set(InternalApiFilter.HEADER, token);
        }
        return execution.execute(request, body);
    }

    @Override
    public void customize(RestClient.Builder builder) {
        builder.requestInterceptor(this);
    }
}
