// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nexaticket.platform.security.InternalApiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link InternalTokenPropagation} lắp vào {@code RestClient.Builder} qua {@code customize} — đúng
 * đường Spring Boot dùng — chứ không gọi thẳng {@code intercept}.
 *
 * <p>{@code InternalTokenPropagationTest} đã kiểm bốn luật trên interceptor đơn lẻ; ở đây kiểm phần
 * còn thiếu: customizer có thật sự gắn interceptor vào client dựng ra không, và các đường dẫn gần
 * giống {@code /internal/} có bị gắn nhầm bí mật không.
 */
class InternalTokenPropagationWireTest {

    private static RestClient client(InternalApiProperties props, MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ordering-service:8080");
        new InternalTokenPropagation(props).customize(builder);
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        return builder.build();
    }

    @Test
    @DisplayName("customize gắn interceptor: client dựng từ builder gửi bí mật tới /internal/**")
    void customize_gan_interceptor() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        RestClient client = client(new InternalApiProperties("s3cret"), server);
        server[0]
                .expect(requestTo("http://ordering-service:8080/internal/orders/42"))
                .andExpect(header(InternalApiFilter.HEADER, "s3cret"))
                .andRespond(withSuccess());

        client.get().uri("/internal/orders/42").retrieve().toBodilessEntity();

        server[0].verify();
    }

    @Test
    @DisplayName("đường gần giống (/internals, /v1/internal, /internal không gạch cuối): không gắn bí mật")
    void duong_gan_giong_khong_gan() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        RestClient client = client(new InternalApiProperties("s3cret"), server);
        for (String path : new String[] {"/internals/x", "/v1/internal/x", "/internal"}) {
            server[0]
                    .expect(requestTo("http://ordering-service:8080" + path))
                    .andExpect(headerDoesNotExist(InternalApiFilter.HEADER))
                    .andRespond(withSuccess());
        }

        client.get().uri("/internals/x").retrieve().toBodilessEntity();
        client.get().uri("/v1/internal/x").retrieve().toBodilessEntity();
        client.get().uri("/internal").retrieve().toBodilessEntity();

        server[0].verify();
    }

    @Test
    @DisplayName("bí mật null: không gắn gì, không NPE")
    void bi_mat_null_khong_gan() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        RestClient client = client(new InternalApiProperties(null), server);
        server[0]
                .expect(requestTo("http://ordering-service:8080/internal/orders/42"))
                .andExpect(headerDoesNotExist(InternalApiFilter.HEADER))
                .andRespond(withSuccess());

        client.get().uri("/internal/orders/42").retrieve().toBodilessEntity();

        server[0].verify();
    }
}
