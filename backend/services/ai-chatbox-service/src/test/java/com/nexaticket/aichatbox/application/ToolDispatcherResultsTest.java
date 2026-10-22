// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.agent.SupportAgentTools;
import com.nexaticket.aichatbox.application.agent.ToolDispatcher;
import com.nexaticket.aichatbox.application.booking.TicketBookingUseCase;
import com.nexaticket.aichatbox.domain.model.CustomerProfile;
import com.nexaticket.aichatbox.domain.model.EventBrief;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.EventRef;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.model.RefundPolicy;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.CatalogClientPort;
import com.nexaticket.aichatbox.domain.port.OrderingClientPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.support.FakeCommerce;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Hình dạng kết quả mà mô hình đọc cho từng tool, và nhất là ranh giới giữa ba kết cục: có dữ liệu,
 * không có dữ liệu (thành công, {@code found:false}), và không tra được (lỗi).
 */
@DisplayName("ToolDispatcher — kết quả từng tool")
class ToolDispatcherResultsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Danh mục giả tuỳ biến được kết quả tìm kiếm và lỗi. */
    private static final class ScriptedCatalog implements CatalogClientPort {
        List<EventBrief> found = List.of();
        final List<String> queries = new ArrayList<>();
        final FakeCommerce.Catalog details = new FakeCommerce.Catalog();
        boolean down;

        @Override
        public List<EventBrief> searchEvents(String query, String city, String category, int limit) {
            queries.add(query + "|" + city + "|" + category);
            if (down) {
                throw new RemoteCallException("catalog-service", "giả: hỏng", null);
            }
            return found;
        }

        @Override
        public Optional<EventDetail> findEventBySlug(String slug) {
            if (down) {
                throw new RemoteCallException("catalog-service", "giả: hỏng", null);
            }
            return details.findEventBySlug(slug);
        }

        @Override
        public Optional<ZoneAdmission> findZoneAdmission(String slug, String zoneCode) {
            if (down) {
                throw new RemoteCallException("catalog-service", "giả: hỏng", null);
            }
            return details.findZoneAdmission(slug, zoneCode);
        }
    }

    private final ScriptedCatalog catalog = new ScriptedCatalog();
    private final FakeCommerce.Ordering ordering = new FakeCommerce.Ordering();
    private final FakeCommerce.Inventory inventory = new FakeCommerce.Inventory();
    private final FakeCommerce.Knowledge knowledge = new FakeCommerce.Knowledge();
    private final FakeCommerce.Profiles profiles = new FakeCommerce.Profiles();
    private final FakeCommerce.Credentials credentials = new FakeCommerce.Credentials();
    private final UUID sessionId = UUID.randomUUID();

    private ToolDispatcher dispatcher() {
        return dispatcher(ordering);
    }

    private ToolDispatcher dispatcher(OrderingClientPort orderingPort) {
        return new ToolDispatcher(
                orderingPort,
                catalog,
                knowledge,
                credentials,
                profiles,
                new TicketBookingUseCase(catalog, inventory, orderingPort, credentials),
                JSON,
                new AgentMetrics(new SimpleMeterRegistry()));
    }

    private static ToolInvocation call(String tool, Map<String, Object> args) {
        return new ToolInvocation(UUID.randomUUID().toString(), tool, args);
    }

    private static JsonNode json(ToolDispatcher.DispatchResult result) throws Exception {
        return JSON.readTree(result.outcome().payload());
    }

    @Nested
    @DisplayName("tool không tồn tại và lưới cuối")
    class LuoiCuoi {

        @Test
        @DisplayName("tool lạ trả LỖI nêu tên tool, không ném — để vòng sau vẫn đủ kết quả cho mọi lời gọi")
        void tool_la() {
            ToolInvocation call = call("hackTheSystem", Map.of());

            var result = dispatcher().dispatch(call);

            assertThat(result.outcome().failed()).isTrue();
            assertThat(result.outcome().callId()).isEqualTo(call.callId());
            assertThat(result.outcome().payload()).contains("Tool không tồn tại: hackTheSystem");
            assertThat(result.events()).isEmpty();
        }

        @Test
        @DisplayName("ngoại lệ không lường trước trong cổng thành LỖI chung, không lộ chi tiết nội bộ")
        void ngoai_le_bat_ngo() {
            OrderingClientPort exploding = new FakeCommerce.Ordering() {
                @Override
                public OrderSummary fetchOrder(UUID orderId, String callerAccessToken) {
                    throw new IllegalStateException("mat khau db = hunter2");
                }
            };

            var result = dispatcher(exploding)
                    .dispatch(call(
                            SupportAgentTools.GET_ORDER_STATUS,
                            Map.of("orderId", UUID.randomUUID().toString())));

            assertThat(result.outcome().failed()).isTrue();
            assertThat(result.outcome().payload()).contains("sự cố").doesNotContain("hunter2");
        }
    }

    @Nested
    @DisplayName("getOrderStatus")
    class OrderStatus {

        @Test
        @DisplayName("mã đơn không phải UUID là found:false kèm gợi ý, KHÔNG gọi ordering và KHÔNG là lỗi")
        void ma_don_khong_hop_le() throws Exception {
            for (Object bad : new Object[] {"khong-phai-uuid", "", "   ", 12345}) {
                var result = dispatcher().dispatch(call(SupportAgentTools.GET_ORDER_STATUS, Map.of("orderId", bad)));

                assertThat(result.outcome().failed()).as("orderId=%s", bad).isFalse();
                assertThat(json(result).get("found").asBoolean()).isFalse();
                assertThat(json(result).get("reason").asText()).isEqualTo("MÃ ĐƠN KHÔNG HỢP LỆ");
                assertThat(json(result).get("hint").asText()).contains("getCustomerProfileAndHistory");
            }
            var missing = dispatcher().dispatch(call(SupportAgentTools.GET_ORDER_STATUS, Map.of()));
            assertThat(json(missing).get("reason").asText()).isEqualTo("MÃ ĐƠN KHÔNG HỢP LỆ");
        }

        @Test
        @DisplayName("đơn không có (hoặc của người khác) là THÀNH CÔNG với found:false")
        void khong_co_don() throws Exception {
            var result = dispatcher()
                    .dispatch(call(
                            SupportAgentTools.GET_ORDER_STATUS,
                            Map.of("orderId", UUID.randomUUID().toString())));

            assertThat(result.outcome().failed()).isFalse();
            assertThat(json(result).get("found").asBoolean()).isFalse();
            assertThat(json(result).get("reason").asText()).contains("KHÔNG CÓ ĐƠN NÀY");
        }

        @Test
        @DisplayName("ordering hỏng là LỖI dặn mô hình đừng nói 'không tìm thấy đơn'")
        void ordering_hong() {
            ordering.down = true;

            var result = dispatcher()
                    .dispatch(call(
                            SupportAgentTools.GET_ORDER_STATUS,
                            Map.of("orderId", UUID.randomUUID().toString())));

            assertThat(result.outcome().failed()).isTrue();
            assertThat(result.outcome().payload()).contains("ĐỪNG nói là không tìm thấy đơn");
        }

        @Test
        @DisplayName("đơn đã thanh toán: giờ Việt Nam đã định dạng, từng vé, và không có hạn thanh toán")
        void don_da_thanh_toan() throws Exception {
            OrderSummary order =
                    ordering.put(FakeCommerce.paidOrder(UUID.randomUUID(), Instant.parse("2026-10-07T03:00:00Z")));

            var result = dispatcher()
                    .dispatch(call(
                            SupportAgentTools.GET_ORDER_STATUS,
                            Map.of("orderId", order.orderId().toString())));

            JsonNode node = json(result);
            assertThat(node.get("found").asBoolean()).isTrue();
            assertThat(node.get("orderNumber").asText()).isEqualTo("NT-240001");
            assertThat(node.get("status").asText()).isEqualTo("PAID");
            assertThat(node.get("totalVnd").asLong()).isEqualTo(1_800_000L);
            assertThat(node.get("paidVietnamTime").asText()).isEqualTo("10:00 ngày 7/10/2026");
            assertThat(node.has("paymentExpiresVietnamTime")).isFalse();
            assertThat(node.get("items").get(0).get("description").asText()).isEqualTo("Khán đài A · ghế 20");
            assertThat(node.get("items").get(0).get("unitPriceVnd").asLong()).isEqualTo(1_800_000L);
        }

        @Test
        @DisplayName("đơn chờ thanh toán: có hạn thanh toán, chưa có giờ đã trả")
        void don_cho_thanh_toan() throws Exception {
            OrderSummary order = ordering.put(FakeCommerce.unpaidOrder(UUID.randomUUID()));

            var result = dispatcher()
                    .dispatch(call(SupportAgentTools.GET_ORDER_STATUS, Map.of("orderId", " " + order.orderId() + " ")));

            JsonNode node = json(result);
            assertThat(node.get("status").asText()).isEqualTo("AWAITING_PAYMENT");
            assertThat(node.has("paymentExpiresVietnamTime")).isTrue();
            assertThat(node.has("paidVietnamTime")).isFalse();
        }

        @Test
        @DisplayName("token của khách không bao giờ có trong kết quả đưa cho mô hình")
        void token_khong_vao_ket_qua() {
            OrderSummary order = ordering.put(FakeCommerce.paidOrder(UUID.randomUUID(), Instant.now()));

            var result = dispatcher()
                    .dispatch(call(
                            SupportAgentTools.GET_ORDER_STATUS,
                            Map.of("orderId", order.orderId().toString())));

            assertThat(result.outcome().payload()).doesNotContain(FakeCommerce.TOKEN);
        }
    }

    @Nested
    @DisplayName("getCustomerProfileAndHistory")
    class ProfileAndHistory {

        @Test
        @DisplayName("khách chưa có đơn: orders rỗng kèm gợi ý hỏi tài khoản khác")
        void chua_co_don() throws Exception {
            var result = dispatcher().dispatch(call(SupportAgentTools.GET_CUSTOMER_PROFILE_AND_HISTORY, Map.of()));

            JsonNode node = json(result);
            assertThat(result.outcome().failed()).isFalse();
            assertThat(node.get("orderCount").asInt()).isZero();
            assertThat(node.get("orders")).isEmpty();
            assertThat(node.get("hint").asText()).contains("tài khoản khác");
        }

        @Test
        @DisplayName("chỉ lấy tối đa 5 đơn gần nhất, mỗi đơn có số vé và mốc thời gian phù hợp trạng thái")
        void toi_da_nam_don() throws Exception {
            for (int i = 0; i < 7; i++) {
                ordering.mine.add(FakeCommerce.paidOrder(UUID.randomUUID(), Instant.parse("2026-10-07T03:00:00Z")));
            }
            ordering.mine.add(0, FakeCommerce.unpaidOrder(UUID.randomUUID()));

            JsonNode node =
                    json(dispatcher().dispatch(call(SupportAgentTools.GET_CUSTOMER_PROFILE_AND_HISTORY, Map.of())));

            assertThat(node.get("orderCount").asInt()).isEqualTo(5);
            assertThat(node.get("orders")).hasSize(5);
            JsonNode unpaid = node.get("orders").get(0);
            assertThat(unpaid.get("ticketCount").asInt()).isEqualTo(1);
            assertThat(unpaid.has("paymentExpiresVietnamTime")).isTrue();
            assertThat(unpaid.has("paidVietnamTime")).isFalse();
            assertThat(node.get("orders").get(1).has("paidVietnamTime")).isTrue();
        }

        @Test
        @DisplayName("che email và số điện thoại ở mọi dạng, kể cả thiếu hoặc quá ngắn")
        void che_thong_tin_lien_he() throws Exception {
            record Case(String email, String phone, String maskedEmail, String maskedPhone) {}
            List<Case> cases = List.of(
                    new Case("ab@x.vn", "0912345678", "ab***@x.vn", "09*****678"),
                    new Case("a@x.vn", "+84 912 345 678", "a***@x.vn", "+8*******678"),
                    new Case("khong-co-a-cong", "12345", null, "***"),
                    new Case("@x.vn", "", null, null),
                    new Case(null, null, null, null),
                    new Case("  ", "  ", null, null));
            for (Case c : cases) {
                profiles.profile = new CustomerProfile("Tên", c.email(), c.phone());

                JsonNode customer = json(dispatcher()
                                .dispatch(call(SupportAgentTools.GET_CUSTOMER_PROFILE_AND_HISTORY, Map.of())))
                        .get("customer");

                assertThat(
                                customer.get("emailMasked").isNull()
                                        ? null
                                        : customer.get("emailMasked").asText())
                        .as("email=%s", c.email())
                        .isEqualTo(c.maskedEmail());
                assertThat(
                                customer.get("phoneMasked").isNull()
                                        ? null
                                        : customer.get("phoneMasked").asText())
                        .as("phone=%s", c.phone())
                        .isEqualTo(c.maskedPhone());
            }
        }
    }

    @Nested
    @DisplayName("findEvents")
    class FindEvents {

        private EventBrief brief(String slug, String title, int sessions, Instant next, Long price) {
            return new EventBrief(slug, title, "nhac-song", "Hà Nội", "Nhà hát Lớn", next, price, sessions);
        }

        @Test
        @DisplayName("không ra gì là THÀNH CÔNG found:false kèm gợi ý, và không kèm EventRef nào")
        void khong_ra_gi() throws Exception {
            var result = dispatcher().dispatch(call(SupportAgentTools.FIND_EVENTS, Map.of("query", "Rồng Vàng")));

            assertThat(result.outcome().failed()).isFalse();
            assertThat(json(result).get("found").asBoolean()).isFalse();
            assertThat(json(result).get("hint").asText()).contains("chưa bán vé");
            assertThat(result.events()).isEmpty();
        }

        @Test
        @DisplayName("ra kết quả: giờ Việt Nam, giá, và mọi sự kiện đều đi ra ngoài dưới dạng EventRef theo thứ tự")
        void ra_ket_qua() throws Exception {
            catalog.found = List.of(
                    brief("dem-nhac-trinh", "Đêm nhạc Trịnh", 1, Instant.parse("2026-11-20T13:00:00Z"), 450_000L),
                    brief("kich-noi", "Kịch nói", 1, null, null));

            var result = dispatcher()
                    .dispatch(call(
                            SupportAgentTools.FIND_EVENTS, Map.of("query", "nhạc", "city", "Hà Nội", "category", "x")));

            JsonNode node = json(result);
            assertThat(node.get("found").asBoolean()).isTrue();
            assertThat(node.get("count").asInt()).isEqualTo(2);
            JsonNode first = node.get("events").get(0);
            assertThat(first.get("nextSessionVietnamTime").asText()).isEqualTo("20:00 ngày 20/11/2026");
            assertThat(first.get("fromPriceVnd").asLong()).isEqualTo(450_000L);
            assertThat(first.has("otherSessionDates")).isFalse();
            JsonNode second = node.get("events").get(1);
            assertThat(second.has("nextSessionVietnamTime")).isFalse();
            assertThat(second.has("fromPriceVnd")).isFalse();
            assertThat(result.events())
                    .containsExactly(
                            new EventRef("dem-nhac-trinh", "Đêm nhạc Trịnh"), new EventRef("kich-noi", "Kịch nói"));
            assertThat(catalog.queries).containsExactly("nhạc|Hà Nội|x");
        }

        @Test
        @DisplayName("nhiều suất: nói THẲNG là ngày các suất còn lại không có ở đây để mô hình không tự bịa")
        void nhieu_suat() throws Exception {
            catalog.found = List.of(brief("a", "A", 3, Instant.parse("2026-10-02T13:00:00Z"), 1L));

            JsonNode event = json(dispatcher().dispatch(call(SupportAgentTools.FIND_EVENTS, Map.of("query", "a"))))
                    .get("events")
                    .get(0);

            assertThat(event.get("otherSessionDates").asText())
                    .contains("còn 2 suất khác")
                    .contains("ĐỪNG tự nêu ngày");
        }

        @Test
        @DisplayName("tham số bỏ trống được chuyển nguyên là null cho cổng danh mục")
        void tham_so_bo_trong() {
            dispatcher().dispatch(call(SupportAgentTools.FIND_EVENTS, Map.of()));

            assertThat(catalog.queries).containsExactly("null|null|null");
        }

        @Test
        @DisplayName("catalog hỏng là LỖI dặn đừng nói 'không có sự kiện nào'")
        void catalog_hong() {
            catalog.down = true;

            var result = dispatcher().dispatch(call(SupportAgentTools.FIND_EVENTS, Map.of("query", "x")));

            assertThat(result.outcome().failed()).isTrue();
            assertThat(result.outcome().payload()).contains("ĐỪNG nói là không có sự kiện nào");
            assertThat(result.events()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getEventDetails")
    class EventDetails {

        @Test
        @DisplayName("thiếu slug hoặc slug trống là found:false kèm gợi ý gọi findEvents")
        void thieu_slug() throws Exception {
            for (Map<String, Object> args : List.of(Map.<String, Object>of(), Map.<String, Object>of("slug", "  "))) {
                var result = dispatcher().dispatch(call(SupportAgentTools.GET_EVENT_DETAILS, args));

                assertThat(result.outcome().failed()).isFalse();
                assertThat(json(result).get("reason").asText()).isEqualTo("THIẾU SLUG");
            }
        }

        @Test
        @DisplayName("slug không có thật là found:false dặn đừng tự dựng slug từ tên")
        void slug_khong_co() throws Exception {
            var result = dispatcher().dispatch(call(SupportAgentTools.GET_EVENT_DETAILS, Map.of("slug", "tu-bia")));

            assertThat(result.outcome().failed()).isFalse();
            assertThat(json(result).get("hint").asText()).contains("Đừng tự dựng slug");
            assertThat(result.events()).isEmpty();
        }

        @Test
        @DisplayName("có dữ liệu: đủ suất, giờ Việt Nam, hạng vé, đường dẫn cho khách và một EventRef")
        void co_du_lieu() throws Exception {
            UUID eventSessionId = UUID.randomUUID();
            EventDetail detail = new EventDetail(
                    "dem-nhac-trinh",
                    "Đêm nhạc Trịnh",
                    "Tóm tắt",
                    "Mô tả dài",
                    "nhac-song",
                    "Hà Nội",
                    "Nhà hát Lớn",
                    "1 Tràng Tiền",
                    List.of(new EventDetail.Session(
                            eventSessionId,
                            Instant.parse("2026-11-20T13:00:00Z"),
                            null,
                            Instant.parse("2026-11-20T12:00:00Z"),
                            List.of(new EventDetail.Tier("VIP", 1_800_000L, "A", "Khán đài A")))));
            catalog.details.put(detail, ZoneAdmission.SEATED);

            var result = dispatcher()
                    .dispatch(call(SupportAgentTools.GET_EVENT_DETAILS, Map.of("slug", "  dem-nhac-trinh  ")));

            JsonNode node = json(result);
            assertThat(node.get("found").asBoolean()).isTrue();
            assertThat(node.get("summary").asText()).isEqualTo("Tóm tắt");
            assertThat(node.get("description").asText()).isEqualTo("Mô tả dài");
            assertThat(node.get("venueAddress").asText()).isEqualTo("1 Tràng Tiền");
            assertThat(node.get("customerUrl").asText()).isEqualTo("/events/dem-nhac-trinh");
            JsonNode session = node.get("sessions").get(0);
            assertThat(session.get("sessionId").asText()).isEqualTo(eventSessionId.toString());
            assertThat(session.get("startsVietnamTime").asText()).isEqualTo("20:00 ngày 20/11/2026");
            assertThat(session.get("ticketSalesCloseVietnamTime").asText()).isEqualTo("19:00 ngày 20/11/2026");
            assertThat(session.get("tiers").get(0).get("priceVnd").asLong()).isEqualTo(1_800_000L);
            assertThat(result.events()).containsExactly(new EventRef("dem-nhac-trinh", "Đêm nhạc Trịnh"));
        }

        @Test
        @DisplayName("trường tuỳ chọn vắng mặt thì không xuất hiện trong kết quả")
        void truong_tuy_chon_vang() throws Exception {
            catalog.details.put(
                    new EventDetail(
                            "a",
                            "A",
                            null,
                            null,
                            "k",
                            "HN",
                            "NH",
                            null,
                            List.of(new EventDetail.Session(UUID.randomUUID(), null, null, null, List.of()))),
                    ZoneAdmission.SEATED);

            JsonNode node = json(dispatcher().dispatch(call(SupportAgentTools.GET_EVENT_DETAILS, Map.of("slug", "a"))));

            assertThat(node.has("summary")).isFalse();
            assertThat(node.has("description")).isFalse();
            assertThat(node.has("venueAddress")).isFalse();
            assertThat(node.get("sessions").get(0).has("startsVietnamTime")).isFalse();
            assertThat(node.get("sessions").get(0).has("ticketSalesCloseVietnamTime"))
                    .isFalse();
        }

        @Test
        @DisplayName("catalog hỏng là LỖI")
        void catalog_hong() {
            catalog.down = true;

            var result = dispatcher().dispatch(call(SupportAgentTools.GET_EVENT_DETAILS, Map.of("slug", "a")));

            assertThat(result.outcome().failed()).isTrue();
            assertThat(result.outcome().payload()).contains("thử lại sau");
        }
    }

    @Nested
    @DisplayName("getEventRules")
    class EventRulesTool {

        @Test
        @DisplayName("mã sự kiện không hợp lệ là found:false, không phải lỗi")
        void ma_khong_hop_le() throws Exception {
            for (Map<String, Object> args :
                    List.of(Map.<String, Object>of(), Map.<String, Object>of("eventId", "xyz"))) {
                var result = dispatcher().dispatch(call(SupportAgentTools.GET_EVENT_RULES, args));

                assertThat(result.outcome().failed()).isFalse();
                assertThat(json(result).get("reason").asText()).isEqualTo("MÃ SỰ KIỆN KHÔNG HỢP LỆ");
            }
        }

        @Test
        @DisplayName("chưa công bố quy định là found:false — mô hình không được bịa quy định")
        void chua_cong_bo() throws Exception {
            var result = dispatcher()
                    .dispatch(call(
                            SupportAgentTools.GET_EVENT_RULES,
                            Map.of("eventId", UUID.randomUUID().toString())));

            assertThat(result.outcome().failed()).isFalse();
            assertThat(json(result).get("reason").asText()).contains("CHƯA CÓ QUY ĐỊNH");
        }

        @Test
        @DisplayName("chính sách hoàn vé nói bằng câu: không hoàn / xét từng trường hợp / có hạn giờ")
        void chinh_sach_hoan_ve() throws Exception {
            record Case(RefundPolicy policy, String expected) {}
            List<Case> cases = List.of(
                    new Case(RefundPolicy.NONE, "KHÔNG nhận hoàn vé"),
                    new Case(new RefundPolicy(true, 0), "nhân viên xem xét từng trường hợp"),
                    new Case(new RefundPolicy(true, 72), "trong 72 giờ kể từ lúc thanh toán"));
            for (Case c : cases) {
                UUID eventId = UUID.randomUUID();
                knowledge.publishRules(eventId, "Đêm nhạc Trịnh", c.policy());

                JsonNode node = json(dispatcher()
                        .dispatch(call(SupportAgentTools.GET_EVENT_RULES, Map.of("eventId", eventId.toString()))));

                assertThat(node.get("found").asBoolean()).isTrue();
                assertThat(node.get("eventTitle").asText()).isEqualTo("Đêm nhạc Trịnh");
                assertThat(node.get("rules").asText()).contains("Quy định");
                assertThat(node.get("refundPolicy").asText()).as(c.expected()).contains(c.expected());
            }
        }
    }

    @Nested
    @DisplayName("initiateTicketBooking")
    class Booking {

        private final UUID eventSessionId = UUID.randomUUID();

        private Map<String, Object> args(Object slug, Object session, Object zone, Object quantity) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("slug", slug);
            m.put("sessionId", session);
            m.put("zoneCode", zone);
            m.put("quantity", quantity);
            m.values().removeIf(java.util.Objects::isNull);
            return m;
        }

        @Test
        @DisplayName(
                "thiếu slug, sessionId (hoặc không phải UUID) hay zoneCode thì booked:false THIẾU THAM SỐ, không chạm kho chỗ")
        void thieu_tham_so() throws Exception {
            String session = eventSessionId.toString();
            List<Map<String, Object>> bad = List.of(
                    args(null, session, "A", 2),
                    args(" ", session, "A", 2),
                    args("dem", null, "A", 2),
                    args("dem", "khong-phai-uuid", "A", 2),
                    args("dem", session, null, 2),
                    args("dem", session, "  ", 2));
            for (Map<String, Object> arguments : bad) {
                var result = dispatcher().dispatch(call(SupportAgentTools.INITIATE_TICKET_BOOKING, arguments));

                assertThat(result.outcome().failed()).as("%s", arguments).isFalse();
                assertThat(json(result).get("booked").asBoolean()).isFalse();
                assertThat(json(result).get("reason").asText()).isEqualTo("THIẾU THAM SỐ");
            }
            assertThat(inventory.holdKeys).isEmpty();
        }

        @Test
        @DisplayName("số lượng không phải số là THIẾU SỐ LƯỢNG; mô hình điền '2' dạng chuỗi vẫn hiểu là hai vé")
        void so_luong() throws Exception {
            catalog.details.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
            ordering.nextOrder = new PlacedOrder(UUID.randomUUID(), "NT-1", 100L, "https://pay/x", null);
            String session = eventSessionId.toString();

            var notNumber = dispatcher()
                    .dispatch(call(
                            SupportAgentTools.INITIATE_TICKET_BOOKING, args("dem-nhac-trinh", session, "A", "hai")));
            assertThat(json(notNumber).get("reason").asText()).isEqualTo("THIẾU SỐ LƯỢNG");

            var asString = dispatcher()
                    .dispatch(call(
                            SupportAgentTools.INITIATE_TICKET_BOOKING, args("dem-nhac-trinh", session, "A", " 2 ")));
            JsonNode node = json(asString);
            assertThat(node.get("booked").asBoolean()).isTrue();
            assertThat(node.get("quantity").asInt()).isEqualTo(2);
            // Đơn không có hạn thanh toán thì không bịa ra một mốc.
            assertThat(node.has("paymentExpiresVietnamTime")).isFalse();
            assertThat(node.get("checkoutUrl").asText()).isEqualTo("https://pay/x");
            assertThat(node.get("nextStep").asText()).contains("ĐỪNG tự viết đường dẫn khác");
        }

        @Test
        @DisplayName("vượt trần số vé qua chat là booked:false kèm lý do, không phải lỗi")
        void vuot_tran() throws Exception {
            catalog.details.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);

            var result = dispatcher()
                    .dispatch(call(
                            SupportAgentTools.INITIATE_TICKET_BOOKING,
                            args("dem-nhac-trinh", eventSessionId.toString(), "A", 50)));

            assertThat(result.outcome().failed()).isFalse();
            assertThat(json(result).get("booked").asBoolean()).isFalse();
            assertThat(json(result).get("reason").asText()).contains("tối đa 6 vé");
            assertThat(inventory.holdKeys).isEmpty();
        }

        @Test
        @DisplayName("ordering không phản hồi cả hai lần: báo 'chưa xác nhận', dặn đừng đặt lại, và GIỮ chỗ")
        void ordering_hong_luc_dat_don() throws Exception {
            catalog.details.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
            ordering.placeFailure = new RemoteCallException("ordering-service", "giả: hỏng", null);

            var result = dispatcher()
                    .dispatch(call(
                            SupportAgentTools.INITIATE_TICKET_BOOKING,
                            args("dem-nhac-trinh", eventSessionId.toString(), "A", 2)));

            // Epic 6 (R-25): ordering quá hạn thì chưa biết đơn đã tạo chưa. Hỏi lại cùng khoá một lần;
            // vẫn không biết thì KHÔNG nhả chỗ (đơn có thể đã commit) và dặn mô hình đừng đặt lại —
            // kết quả là booked=false chứ không phải lỗi tool.
            assertThat(result.outcome().failed()).isFalse();
            assertThat(json(result).get("booked").asBoolean()).isFalse();
            assertThat(json(result).get("reason").asText()).contains("Đừng gọi đặt vé lại");
            assertThat(inventory.released).isEmpty();
        }

        @Test
        @DisplayName("khoá idempotency suy từ callId của lời gọi: cùng lời gọi lặp lại không đặt hai đơn")
        void khoa_idempotency_theo_call_id() {
            catalog.details.put(FakeCommerce.sampleEvent(eventSessionId), ZoneAdmission.SEATED);
            ordering.nextOrder = new PlacedOrder(UUID.randomUUID(), "NT-1", 100L, "https://pay/x", null);
            ToolInvocation call = call(
                    SupportAgentTools.INITIATE_TICKET_BOOKING,
                    args("dem-nhac-trinh", eventSessionId.toString(), "A", 1));
            ToolDispatcher dispatcher = dispatcher();

            dispatcher.dispatch(call);
            dispatcher.dispatch(call);

            assertThat(inventory.holdKeys).containsExactly("chat-hold-" + call.callId(), "chat-hold-" + call.callId());
            assertThat(ordering.placeKeys)
                    .containsExactly("chat-order-" + call.callId(), "chat-order-" + call.callId());
        }
    }
}
