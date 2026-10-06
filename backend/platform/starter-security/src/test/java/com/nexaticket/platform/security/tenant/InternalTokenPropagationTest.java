// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.platform.security.InternalApiProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Bốn luật, mỗi luật một lý do đã trả giá:
 *
 * <ul>
 *   <li>Gắn ở {@code /internal/**}: mười một adapter đã quên, production đứt chuỗi đơn → vé.
 *   <li>KHÔNG gắn ở đường khác: cùng builder dựng client gọi payOS — secret không được rời hệ.
 *   <li>KHÔNG đè header đã có: catalog tự gắn tay, hai cơ chế không được tranh nhau.
 *   <li>Không secret thì không làm gì: máy dev không khai, và phía nhận cũng không đòi.
 * </ul>
 */
class InternalTokenPropagationTest {

    @Test
    void ganHeaderChoDuongNoiBo() throws IOException {
        HttpHeaders sent =
                run(new InternalApiProperties("s3cret"), "http://inventory-service:8080/internal/reservations", null);
        assertThat(sent.getFirst(InternalApiFilter.HEADER)).isEqualTo("s3cret");
    }

    @Test
    void khongGanChoDuongCongKhai() throws IOException {
        HttpHeaders sent =
                run(new InternalApiProperties("s3cret"), "https://api-merchant.payos.vn/v2/payment-requests", null);
        assertThat(sent.containsKey(InternalApiFilter.HEADER)).isFalse();
    }

    @Test
    void khongDeHeaderDaCo() throws IOException {
        HttpHeaders sent =
                run(new InternalApiProperties("s3cret"), "http://identity-service:8080/internal/memberships", "tay");
        assertThat(sent.getFirst(InternalApiFilter.HEADER)).isEqualTo("tay");
    }

    @Test
    void khongSecretThiKhongLamGi() throws IOException {
        HttpHeaders sent =
                run(new InternalApiProperties(" "), "http://inventory-service:8080/internal/reservations", null);
        assertThat(sent.containsKey(InternalApiFilter.HEADER)).isFalse();
    }

    private static HttpHeaders run(InternalApiProperties props, String url, String presetHeader) throws IOException {
        InternalTokenPropagation sut = new InternalTokenPropagation(props);
        HttpHeaders headers = new HttpHeaders();
        if (presetHeader != null) {
            headers.set(InternalApiFilter.HEADER, presetHeader);
        }
        HttpRequest request = new HttpRequest() {
            @Override
            public HttpMethod getMethod() {
                return HttpMethod.POST;
            }

            @Override
            public URI getURI() {
                return URI.create(url);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }

            @Override
            public java.util.Map<String, Object> getAttributes() {
                return new java.util.HashMap<>();
            }
        };
        HttpHeaders[] captured = new HttpHeaders[1];
        ClientHttpRequestExecution execution = (req, body) -> {
            captured[0] = req.getHeaders();
            return new ClientHttpResponse() {
                @Override
                public HttpStatusCode getStatusCode() {
                    return HttpStatusCode.valueOf(200);
                }

                @Override
                public String getStatusText() {
                    return "OK";
                }

                @Override
                public void close() {}

                @Override
                public InputStream getBody() {
                    return new ByteArrayInputStream(new byte[0]);
                }

                @Override
                public HttpHeaders getHeaders() {
                    return new HttpHeaders();
                }
            };
        };
        try (ClientHttpResponse ignored = sut.intercept(request, new byte[0], execution)) {
            return captured[0];
        }
    }
}
