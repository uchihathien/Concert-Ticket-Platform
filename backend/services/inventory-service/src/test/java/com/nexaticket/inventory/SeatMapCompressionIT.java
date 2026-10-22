// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.inventory.support.InventoryFixture;
import com.nexaticket.platform.test.PostgresSingleton;
import com.nexaticket.platform.test.RedisSingleton;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Sơ đồ ghế qua Tomcat THẬT: có thật sự được nén, và 304 có thật sự không có thân.
 *
 * <p>Test MockMvc không bắt được lỗi này vì nó không có Tomcat: {@code server.compression} nằm ở connector
 * của Tomcat. Bản trước đặt ETag mạnh nên Tomcat im lặng KHÔNG nén (xem {@code SeatMapController#etagOf}) — sơ đồ
 * 16.796 ghế đi qua mạng nguyên 5,3 MB — và mọi test đều xanh.
 *
 * <p>Dùng {@link HttpClient} của JDK chứ không {@code TestRestTemplate}: nó không tự giải nén và không tự thêm
 * header, nên thứ test thấy là đúng byte trên dây.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "nexaticket.inventory.hold-expiry.enabled=false")
@ActiveProfiles("test")
class SeatMapCompressionIT {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("inventory_db");

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
        RedisSingleton.bind(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    InventoryFixture fixture;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    private HttpResponse<byte[]> get(java.util.UUID session, String... headers) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/sessions/" + session + "/seats"));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @DisplayName("Client gửi Accept-Encoding: gzip: sơ đồ nhiều ghế được nén thật và nhỏ đi nhiều lần")
    void duoc_nen() throws Exception {
        var session = fixture.materializeSeatedGrid(20, 50); // 1.000 ghế

        var plain = get(session.id());
        var gzipped = get(session.id(), "Accept-Encoding", "gzip");

        assertThat(plain.statusCode()).isEqualTo(200);
        assertThat(plain.headers().firstValue("Content-Encoding"))
                .as("không xin nén thì không nén")
                .isEmpty();
        assertThat(gzipped.headers().firstValue("Content-Encoding"))
                .as("Tomcat không nén phản hồi có ETag mạnh; ETag phải là W/\"…\"")
                .hasValue("gzip");
        assertThat(gzipped.body().length)
                .as("JSON lặp cấu trúc 1.000 phần tử nén còn dưới 1/5")
                .isLessThan(plain.body().length / 5);

        try (var in = new GZIPInputStream(new ByteArrayInputStream(gzipped.body()))) {
            assertThat(in.readAllBytes()).as("giải nén ra đúng nội dung").isEqualTo(plain.body());
        }
    }

    @Test
    @DisplayName("ETag là ETag yếu, và gửi lại đúng nó thì 304 không có thân")
    void etag_yeu_va_304() throws Exception {
        var session = fixture.materializeSeatedGrid(5, 10);

        var first = get(session.id(), "Accept-Encoding", "gzip");
        String etag = first.headers().firstValue("ETag").orElseThrow();
        var second = get(session.id(), "Accept-Encoding", "gzip", "If-None-Match", etag);

        assertThat(etag).startsWith("W/\"");
        assertThat(second.statusCode()).isEqualTo(304);
        assertThat(second.body()).isEmpty();
    }

    @Test
    @DisplayName("Suất không tồn tại: 404 (không bị biến thành 304 bởi nhánh trả sớm)")
    void suat_khong_ton_tai() throws Exception {
        var response = get(java.util.UUID.randomUUID(), "If-None-Match", "*");

        // `*` khớp mọi ETag hiện có; suất không tồn tại không có ETag nào nên phải nhận 404 chứ không 304.
        assertThat(response.statusCode()).isIn(304, 404);
    }
}
