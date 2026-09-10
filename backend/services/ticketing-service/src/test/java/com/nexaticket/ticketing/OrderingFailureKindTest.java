// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.ticketing.domain.port.OrderingPort;
import com.nexaticket.ticketing.infrastructure.http.OrderingHttpAdapter;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Phân biệt lỗi <b>tạm thời</b> với lỗi <b>dứt khoát</b> khi hỏi Ordering.
 *
 * <p>Đây không phải chuyện phân loại cho gọn. Hàng đợi {@code ticketing.ordering.paid} chỉ có một
 * consumer, nên một message không bao giờ xử lý được sẽ <b>chặn đầu hàng</b>: mọi đơn phía sau
 * không được phát vé, và triệu chứng ở phía khách là "đã trả tiền mà không có vé".
 *
 * <p>Đã xảy ra thật. Adapter gói mọi {@code RuntimeException} thành "không đọc được đơn", nên bảy
 * message trỏ vào những đơn không còn tồn tại bị giao lại vô hạn và làm kẹt cả hàng đợi. Log lúc đó
 * chỉ toàn cảnh báo lặp lại của mấy đơn cũ — không có gì chỉ ra rằng đơn mới đang bị bỏ đói.
 *
 * <p>Dùng {@link HttpServer} của JDK thay vì một thư viện giả lập: thứ cần kiểm là adapter phân
 * loại mã HTTP ra sao, và một server trả đúng một con số thì không đáng thêm một dependency.
 */
class OrderingFailureKindTest {

    private HttpServer server;
    private OrderingHttpAdapter adapter;
    private final AtomicInteger statusToReturn = new AtomicInteger(200);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(statusToReturn.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        // Builder tĩnh là ĐỦ ở đây: test này kiểm cách phân loại lỗi, không kiểm việc truyền
        // correlation id — thứ do CorrelationPropagation cắm vào builder của Spring.
        adapter = new OrderingHttpAdapter(
                RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("404: dứt khoát — hỏi lại lần thứ một nghìn cũng vẫn 404")
    void bon_khong_bon_la_dut_khoat() {
        statusToReturn.set(404);

        assertThatThrownBy(() -> adapter.fetch(UUID.randomUUID()))
                .isInstanceOf(OrderingPort.OrderNotFoundException.class);
    }

    @Test
    @DisplayName("400: dứt khoát — request của ta sai, gửi lại y hệt thì vẫn sai")
    void bon_tram_la_dut_khoat() {
        statusToReturn.set(400);

        assertThatThrownBy(() -> adapter.fetch(UUID.randomUUID()))
                .isInstanceOf(OrderingPort.OrderNotFoundException.class);
    }

    @Test
    @DisplayName("429: tạm thời, dù là 4xx — 'chậm lại' nghĩa là thử lại được")
    void bon_hai_chin_la_tam_thoi() {
        // Ngoại lệ duy nhất trong nhóm 4xx. Xếp nhầm nó vào nhóm dứt khoát thì một đợt giới hạn
        // tần suất sẽ đẩy vé của khách sang DLQ thay vì thử lại.
        statusToReturn.set(429);

        assertThatThrownBy(() -> adapter.fetch(UUID.randomUUID()))
                .isInstanceOf(OrderingPort.OrderingUnavailableException.class);
    }

    @Test
    @DisplayName("500: tạm thời — Ordering trục trặc, lát nữa hỏi lại là được")
    void nam_tram_la_tam_thoi() {
        statusToReturn.set(500);

        assertThatThrownBy(() -> adapter.fetch(UUID.randomUUID()))
                .isInstanceOf(OrderingPort.OrderingUnavailableException.class);
    }

    @Test
    @DisplayName("không kết nối được: tạm thời")
    void mat_ket_noi_la_tam_thoi() {
        server.stop(0);

        assertThatThrownBy(() -> adapter.fetch(UUID.randomUUID()))
                .isInstanceOf(OrderingPort.OrderingUnavailableException.class);
    }
}
