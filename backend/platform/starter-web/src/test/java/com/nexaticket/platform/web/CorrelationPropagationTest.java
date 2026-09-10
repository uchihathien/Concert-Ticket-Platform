// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.kernel.id.CorrelationContext;
import com.nexaticket.platform.web.correlation.CorrelationIdFilter;
import com.nexaticket.platform.web.correlation.CorrelationPropagation;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Kiểm rằng correlation id ĐI RA cùng request, không chỉ đi vào.
 *
 * <p>Trước khi có {@link CorrelationPropagation}, mỗi service tự sinh id mới cho request đi ra —
 * nên bốn service ghi bốn id khác nhau cho cùng một hành động của cùng một người, và không có gì
 * nối chúng lại. Mỗi service tự nhất quán, nên không có test đơn lẻ nào phát hiện được: lỗi chỉ
 * hiện ra khi cố đi theo một request xuyên nhiều service.
 *
 * <p>Dùng máy chủ HTTP thật chứ không mock: điều đang kiểm là header có nằm trên dây hay không.
 */
class CorrelationPropagationTest {

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private RestClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String header = exchange.getRequestHeaders().getFirst(CorrelationIdFilter.HEADER);
            received.add(header == null ? "<khong-co>" : header);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        // Đúng cách Spring dựng client: customizer áp lên builder, rồi builder dựng client.
        RestClient.Builder builder = RestClient.builder();
        new CorrelationPropagation().customize(builder);
        client = builder.baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        CorrelationContext.clear();
    }

    @Test
    @DisplayName("id của request đang xử lý đi theo sang service được gọi")
    void id_di_theo_sang_service_duoc_goi() {
        CorrelationContext.set("abc-123");

        client.get().uri("/bat-ky").retrieve().toBodilessEntity();

        assertThat(received).containsExactly("abc-123");
    }

    @Test
    @DisplayName("ngoài ngữ cảnh request thì KHÔNG bịa ra id")
    void khong_bia_ra_id_khi_khong_co_ngu_canh() {
        // Một job nền không có correlation id thì đúng là không có. Sinh đại một cái ở đây tệ hơn
        // là để trống: nó tạo một id không dẫn tới đâu, và service nhận sẽ ghi nó vào log như thể
        // có nguồn gốc.
        CorrelationContext.clear();

        client.get().uri("/bat-ky").retrieve().toBodilessEntity();

        assertThat(received).containsExactly("<khong-co>");
    }

    @Test
    @DisplayName("lời gọi tự khai id thì giữ nguyên, không ghi đè")
    void khong_ghi_de_id_da_khai() {
        CorrelationContext.set("cua-request-hien-tai");

        client.get()
                .uri("/bat-ky")
                .header(CorrelationIdFilter.HEADER, "tu-khai")
                .retrieve()
                .toBodilessEntity();

        assertThat(received).containsExactly("tu-khai");
    }
}
