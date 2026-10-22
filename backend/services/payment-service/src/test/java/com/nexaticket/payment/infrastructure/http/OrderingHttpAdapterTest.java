// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nexaticket.payment.domain.port.OrderingPort;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * ACL sang ordering-service: dịch câu trả lời của Ordering, và biến MỌI sự cố thành lỗi tạm thời để payOS
 * giao lại webhook.
 *
 * <p>Builder là spy để lời gọi {@code requestFactory(...)} của adapter không đè mất server giả — xem
 * {@code PayosHttpGatewayTest}.
 */
class OrderingHttpAdapterTest {

    private static final String BASE = "http://ordering.test";
    private static final UUID ORDER = UUID.fromString("7a1c2f0e-1111-4222-8333-944455556666");
    private static final String URL = BASE + "/internal/orders/" + ORDER + "/confirm-payment";

    private MockRestServiceServer server;
    private OrderingHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = spy(RestClient.builder());
        server = MockRestServiceServer.bindTo(builder).build();
        doReturn(builder).when(builder).requestFactory(any());
        adapter = new OrderingHttpAdapter(builder, BASE);
    }

    @Nested
    @DisplayName("Ordering trả lời")
    class TraLoi {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"PAID,PAID", "ALREADY_PAID,ALREADY_PAID", "MANUAL_REVIEW,MANUAL_REVIEW"})
        @DisplayName("Ba câu trả lời đã biết được dịch đúng")
        void ba_cau_tra_loi(String outcome, OrderingPort.Confirmation expected) {
            server.expect(requestTo(URL))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess("{\"outcome\":\"" + outcome + "\"}", MediaType.APPLICATION_JSON));

            assertThat(adapter.confirmPayment(ORDER)).isEqualTo(expected);
            server.verify();
        }

        @Test
        @DisplayName("204 không body (Ordering bản cũ): coi như PAID, không biến rolling deploy thành vòng retry")
        void khong_body_la_paid() {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NO_CONTENT));

            assertThat(adapter.confirmPayment(ORDER)).isEqualTo(OrderingPort.Confirmation.PAID);
        }

        @Test
        @DisplayName("Giá trị outcome lạ hoặc trống: coi như PAID, không ném")
        void outcome_la() {
            server.expect(requestTo(URL))
                    .andRespond(withSuccess("{\"outcome\":\"SOMETHING_NEW\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(URL)).andRespond(withSuccess("{\"outcome\":\"  \"}", MediaType.APPLICATION_JSON));

            assertThat(adapter.confirmPayment(ORDER)).isEqualTo(OrderingPort.Confirmation.PAID);
            assertThat(adapter.confirmPayment(ORDER)).isEqualTo(OrderingPort.Confirmation.PAID);
        }
    }

    @Nested
    @DisplayName("Ordering không trả lời được")
    class KhongTraLoi {

        @Test
        @DisplayName("HTTP 500: OrderingUnavailableException — để webhook được giao lại")
        void loi_500() {
            server.expect(requestTo(URL)).andRespond(withServerError());

            assertThatThrownBy(() -> adapter.confirmPayment(ORDER))
                    .isInstanceOf(OrderingPort.OrderingUnavailableException.class)
                    .hasMessageContaining(ORDER.toString());
        }

        @Test
        @DisplayName("Lỗi mạng: OrderingUnavailableException giữ nguyên nguyên nhân gốc")
        void loi_mang() {
            server.expect(requestTo(URL)).andRespond(request -> {
                throw new IOException("Connection refused");
            });

            assertThatThrownBy(() -> adapter.confirmPayment(ORDER))
                    .isInstanceOf(OrderingPort.OrderingUnavailableException.class)
                    .hasRootCauseInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("HTTP 404/409: cũng là 'không phản hồi' — payment không tự kết luận thay Ordering")
        void loi_4xx() {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CONFLICT));

            assertThatThrownBy(() -> adapter.confirmPayment(ORDER))
                    .isInstanceOf(OrderingPort.OrderingUnavailableException.class);
        }

        @Test
        @DisplayName("Body 200 không phải JSON: OrderingUnavailableException, không nuốt thành PAID")
        void body_hong() {
            server.expect(requestTo(URL)).andRespond(withSuccess("<html>oops</html>", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> adapter.confirmPayment(ORDER))
                    .isInstanceOf(OrderingPort.OrderingUnavailableException.class);
        }
    }
}
