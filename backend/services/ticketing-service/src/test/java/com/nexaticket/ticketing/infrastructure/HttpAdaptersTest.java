// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.ExportAuditPort;
import com.nexaticket.ticketing.domain.port.OrderingPort;
import com.nexaticket.ticketing.infrastructure.http.CatalogHttpAdapter;
import com.nexaticket.ticketing.infrastructure.http.IdentityAuditHttpAdapter;
import com.nexaticket.ticketing.infrastructure.http.IdentityHttpAdapter;
import com.nexaticket.ticketing.infrastructure.http.OrderingHttpAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Cách các Anti-Corruption Layer của ticketing đổi câu trả lời HTTP thành ngữ nghĩa của port.
 *
 * <p>Ba kiểu hỏng khác nhau có chủ đích: catalog/identity im lặng thì trả rỗng (dữ liệu tô điểm, ở
 * cửa vào không được chặn hàng người), nhật ký im lặng thì NỔ (không xuất dữ liệu cá nhân mà không
 * để lại dấu vết), ordering thì tách tạm thời và dứt khoát (đã có {@code OrderingFailureKindTest}).
 */
class HttpAdaptersTest {

    private static final UUID SESSION = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID EVENT = UUID.fromString("50000000-0000-4000-8000-000000000002");

    private StubHttpServer server;

    @BeforeEach
    void start() {
        server = new StubHttpServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Nested
    @DisplayName("Catalog: giờ diễn của suất")
    class Catalog {

        private final MutableClock clock = new MutableClock(Instant.parse("2026-10-12T10:00:00Z"));

        private CatalogHttpAdapter adapter() {
            return new CatalogHttpAdapter(RestClient.builder(), clock, server.baseUrl());
        }

        @Test
        @DisplayName("200: đọc đúng sự kiện, tên, giờ diễn; gọi đúng đường dẫn nội bộ")
        void doc_duoc_suat() {
            server.respond(200, sessionJson("\"2026-10-12T16:00:00Z\""));

            var info = adapter().sessionInfo(SESSION);

            assertThat(info)
                    .contains(new CatalogPort.SessionInfo(
                            EVENT,
                            "Đêm nhạc",
                            Instant.parse("2026-10-12T13:00:00Z"),
                            Instant.parse("2026-10-12T16:00:00Z")));
            assertThat(server.received()).singleElement().satisfies(r -> assertThat(r.path())
                    .isEqualTo("/internal/sessions/" + SESSION));
        }

        @Test
        @DisplayName("Không khai giờ kết thúc: endsAt null, vẫn dùng được")
        void khong_co_gio_ket_thuc() {
            server.respond(200, sessionJson("null"));

            assertThat(adapter().sessionInfo(SESSION))
                    .hasValueSatisfying(info -> assertThat(info.endsAt()).isNull());
        }

        @Test
        @DisplayName("Trong một phút chỉ hỏi catalog một lần; quá một phút thì hỏi lại")
        void cache_mot_phut() {
            server.respond(200, sessionJson("null"));
            CatalogHttpAdapter adapter = adapter();

            adapter.sessionInfo(SESSION);
            clock.advance(Duration.ofSeconds(59));
            adapter.sessionInfo(SESSION);
            assertThat(server.received()).hasSize(1);

            clock.advance(Duration.ofSeconds(2));
            adapter.sessionInfo(SESSION);
            assertThat(server.received()).hasSize(2);
        }

        @Test
        @DisplayName("Lỗi không được cache: catalog sống lại thì lần quét kế tiếp thấy ngay")
        void loi_khong_cache() {
            CatalogHttpAdapter adapter = adapter();
            server.respond(503, "{}");
            assertThat(adapter.sessionInfo(SESSION)).isEmpty();

            server.respond(200, sessionJson("null"));
            assertThat(adapter.sessionInfo(SESSION)).isPresent();
        }

        @Test
        @DisplayName("404, thiếu eventId hoặc thiếu giờ diễn: rỗng, không ném")
        void thieu_du_lieu_thi_rong() {
            CatalogHttpAdapter adapter = adapter();

            server.respond(404, "{}");
            assertThat(adapter.sessionInfo(SESSION)).isEmpty();

            server.respond(200, "{\"eventTitle\":\"x\",\"startsAt\":\"2026-10-12T13:00:00Z\"}");
            assertThat(adapter.sessionInfo(SESSION)).isEmpty();

            server.respond(200, "{\"eventId\":\"" + EVENT + "\",\"eventTitle\":\"x\"}");
            assertThat(adapter.sessionInfo(SESSION)).isEmpty();
        }

        @Test
        @DisplayName("Catalog sập hẳn (không kết nối được): rỗng, không ném")
        void mat_ket_noi() {
            CatalogHttpAdapter adapter = adapter();
            server.close();

            assertThat(adapter.sessionInfo(SESSION)).isEmpty();
        }

        private String sessionJson(String endsAt) {
            return "{\"eventId\":\"" + EVENT + "\",\"eventTitle\":\"Đêm nhạc\","
                    + "\"startsAt\":\"2026-10-12T13:00:00Z\",\"endsAt\":" + endsAt + "}";
        }
    }

    @Nested
    @DisplayName("Identity: tên người mua")
    class Identity {

        @Test
        @DisplayName("200 có tên: trả tên")
        void co_ten() {
            UUID user = UUID.randomUUID();
            server.respond(200, "{\"userId\":\"" + user + "\",\"email\":\"a@b.vn\",\"fullName\":\"Phạm Thu Hà\"}");

            assertThat(adapter().displayNameOf(user)).contains("Phạm Thu Hà");
            assertThat(server.received().get(0).path()).isEqualTo("/internal/users/" + user);
        }

        @Test
        @DisplayName("Tên trống, 404 hay 500: đều là rỗng — vé vẫn phát được")
        void moi_kieu_hong_deu_rong() {
            IdentityHttpAdapter adapter = adapter();

            server.respond(200, "{\"fullName\":\"   \"}");
            assertThat(adapter.displayNameOf(UUID.randomUUID())).isEmpty();
            server.respond(200, "{\"fullName\":null}");
            assertThat(adapter.displayNameOf(UUID.randomUUID())).isEmpty();
            server.respond(404, "{}");
            assertThat(adapter.displayNameOf(UUID.randomUUID())).isEmpty();
            server.respond(500, "{}");
            assertThat(adapter.displayNameOf(UUID.randomUUID())).isEmpty();
        }

        private IdentityHttpAdapter adapter() {
            return new IdentityHttpAdapter(RestClient.builder(), server.baseUrl());
        }
    }

    @Nested
    @DisplayName("Nhật ký xuất danh sách người mua")
    class NhatKy {

        @Test
        @DisplayName("Gửi đúng hành động, tổ chức, người thực hiện và chi tiết sang identity")
        void gui_dung_noi_dung() {
            UUID org = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            server.respond(201, "");

            adapter().buyerListExported(org, actor, Map.of("rows", 12));

            var request = server.received().get(0);
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/internal/audit-logs");
            assertThat(request.body())
                    .contains("\"action\":\"BUYER_LIST_EXPORTED\"")
                    .contains("\"organizationId\":\"" + org + "\"")
                    .contains("\"actorUserId\":\"" + actor + "\"")
                    .contains("\"rows\":12");
        }

        @Test
        @DisplayName("Identity trả 500: NỔ AuditUnavailableException, không nuốt")
        void loi_thi_no() {
            server.respond(500, "{}");

            assertThatThrownBy(() -> adapter().buyerListExported(UUID.randomUUID(), UUID.randomUUID(), Map.of()))
                    .isInstanceOf(ExportAuditPort.AuditUnavailableException.class);
        }

        @Test
        @DisplayName("Identity trả 4xx cũng NỔ — mọi lần không ghi được đều chặn lượt xuất")
        void bon_tram_cung_no() {
            server.respond(403, "{}");

            assertThatThrownBy(() -> adapter().buyerListExported(UUID.randomUUID(), UUID.randomUUID(), Map.of()))
                    .isInstanceOf(ExportAuditPort.AuditUnavailableException.class);
        }

        private IdentityAuditHttpAdapter adapter() {
            return new IdentityAuditHttpAdapter(RestClient.builder(), server.baseUrl());
        }
    }

    @Nested
    @DisplayName("Ordering: chi tiết đơn và trạng thái")
    class Ordering {

        @Test
        @DisplayName("200: dịch đơn sang PaidOrder kèm từng dòng")
        void doc_don() {
            UUID order = UUID.randomUUID();
            UUID item = UUID.randomUUID();
            server.respond(
                    200,
                    "{\"id\":\"" + order + "\",\"eventSessionId\":\"" + SESSION + "\",\"organizationId\":\"" + EVENT
                            + "\",\"userId\":\"" + EVENT + "\",\"status\":\"PAID\",\"items\":[{\"orderItemId\":\""
                            + item + "\",\"seatCode\":\"A-1\",\"zoneCode\":\"A\",\"admissionType\":\"SEATED\","
                            + "\"seatLabel\":\"Hàng A ghế 1\",\"ticketTypeName\":\"VIP\"}]}");

            OrderingPort.PaidOrder paid = adapter().fetch(order);

            assertThat(paid.orderId()).isEqualTo(order);
            assertThat(paid.isPaid()).isTrue();
            assertThat(paid.items()).singleElement().satisfies(line -> {
                assertThat(line.orderItemId()).isEqualTo(item);
                assertThat(line.seatLabel()).isEqualTo("Hàng A ghế 1");
                assertThat(line.sessionSeatId()).isNull();
            });
            assertThat(server.received().get(0).path()).isEqualTo("/internal/orders/" + order);
        }

        @Test
        @DisplayName("Đơn không có trường items: danh sách dòng rỗng chứ không null")
        void khong_co_items() {
            server.respond(200, "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PAID\"}");

            assertThat(adapter().fetch(UUID.randomUUID()).items()).isEmpty();
        }

        @Test
        @DisplayName("200 mà thân rỗng: tạm thời (giao lại), không phải 'đơn không tồn tại'")
        void than_rong_la_tam_thoi() {
            server.respond(200, "");

            assertThatThrownBy(() -> adapter().fetch(UUID.randomUUID()))
                    .isInstanceOf(OrderingPort.OrderingUnavailableException.class);
        }

        @Test
        @DisplayName("Trạng thái nhiều đơn: không có đơn nào thì không gọi mạng")
        void khong_co_don_khong_goi() {
            assertThat(adapter().statusesOf(List.of())).isEmpty();
            assertThat(server.received()).isEmpty();
        }

        @Test
        @DisplayName("Trạng thái nhiều đơn: 200 trả map; 500 trả map rỗng, không ném")
        void trang_thai_nhieu_don() {
            UUID order = UUID.randomUUID();
            OrderingHttpAdapter adapter = adapter();

            server.respond(200, "{\"statuses\":{\"" + order + "\":\"REFUNDED\"}}");
            assertThat(adapter.statusesOf(List.of(order))).containsExactly(Map.entry(order, "REFUNDED"));
            assertThat(server.received().get(0).body()).contains(order.toString());

            server.respond(500, "{}");
            assertThat(adapter.statusesOf(List.of(order))).isEmpty();

            server.respond(200, "{}");
            assertThat(adapter.statusesOf(List.of(order))).isEmpty();
        }

        private OrderingHttpAdapter adapter() {
            return new OrderingHttpAdapter(RestClient.builder(), server.baseUrl());
        }
    }

    /** Đồng hồ tua được, để kiểm hạn cache mà không phải ngủ. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
