// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.application.agent.AgentMetrics;
import com.nexaticket.aichatbox.application.handoff.HandoffUseCase;
import com.nexaticket.aichatbox.application.handoff.HandoffViews;
import com.nexaticket.aichatbox.domain.model.ChatMessage;
import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.model.HandoffTrigger;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import com.nexaticket.aichatbox.domain.port.IdentityLookupPort;
import com.nexaticket.aichatbox.support.InMemoryChatHistory;
import com.nexaticket.aichatbox.support.InMemoryHandoffRepository;
import com.nexaticket.platform.web.error.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Quy trình phiếu chuyển tiếp ở tầng application: ai được làm gì với phiếu nào, và phiếu hiện ra
 * cho người trực như thế nào. Kho phiếu và lịch sử là bản trong bộ nhớ; đồng hồ cố định, đẩy tay.
 */
@DisplayName("HandoffUseCase")
class HandoffUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");

    /** Đồng hồ cố định có thể đẩy tới — không ngủ. */
    private static final class MutableClock extends Clock {
        private Instant now = T0;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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

    /** Identity giả: đếm số lần tra để chứng minh việc gộp theo id. */
    private static final class CountingIdentity implements IdentityLookupPort {
        final Map<UUID, String> names = new HashMap<>();
        final Map<UUID, String> emails = new HashMap<>();
        int nameLookups;
        int contactLookups;

        @Override
        public Optional<String> displayNameOf(UUID userId) {
            nameLookups++;
            return Optional.ofNullable(names.get(userId));
        }

        @Override
        public Optional<Contact> contactOf(UUID userId) {
            contactLookups++;
            String name = names.get(userId);
            return name == null ? Optional.empty() : Optional.of(new Contact(name, emails.get(userId)));
        }
    }

    private final MutableClock clock = new MutableClock();
    private final InMemoryHandoffRepository repository = new InMemoryHandoffRepository();
    private final InMemoryChatHistory history = new InMemoryChatHistory();
    private final CountingIdentity identity = new CountingIdentity();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HandoffUseCase handoffs =
            new HandoffUseCase(repository, history, identity, clock, new AgentMetrics(registry));

    private final UUID customer = UUID.randomUUID();
    private final UUID agentA = UUID.randomUUID();
    private final UUID agentB = UUID.randomUUID();

    /** Một phiên có sẵn tin nhắn của khách — phiếu cần phiên tồn tại. */
    private UUID sessionWith(UUID owner, String... userMessages) {
        UUID session = UUID.randomUUID();
        for (String message : userMessages) {
            history.append(session, owner, ChatRole.USER, message);
        }
        return session;
    }

    private Handoff open(UUID session, UUID owner, String question) {
        return handoffs.escalate(session, owner, HandoffTrigger.CUSTOMER_REQUEST, "lý do", question);
    }

    private double openedCount() {
        return registry.find("aichatbox.handoff.opened").counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    @Nested
    @DisplayName("mở phiếu")
    class MoPhieu {

        @Test
        @DisplayName("gắn nhãn ý định từ câu hỏi và cắt bản xem trước ở 500 ký tự kèm dấu …")
        void nhan_y_dinh_va_cat_ban_xem_truoc() {
            UUID session = sessionWith(customer, "x");
            String longQuestion = "hoàn vé " + "a".repeat(800);

            Handoff opened = open(session, customer, "  " + longQuestion + "  ");

            assertThat(opened.intent()).isEqualTo(SupportIntent.REFUND);
            assertThat(opened.lastQuestion()).hasSize(501).endsWith("…").startsWith("hoàn vé");
            assertThat(opened.status()).isEqualTo(HandoffStatus.WAITING);
            assertThat(opened.requestedAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("câu hỏi null cho ra phiếu không có câu hỏi, không NPE")
        void cau_hoi_null() {
            Handoff opened = open(sessionWith(customer, "x"), customer, null);

            assertThat(opened.lastQuestion()).isNull();
        }

        @Test
        @DisplayName("idempotent theo phiên: bấm ba lần ra MỘT phiếu và chỉ đếm metric MỘT lần")
        void idempotent_va_metric_mot_lan() {
            UUID session = sessionWith(customer, "x");
            clock.advance(Duration.ofSeconds(0));

            Handoff first = open(session, customer, "cho tôi gặp người");
            clock.advance(Duration.ofMinutes(1));
            Handoff second = open(session, customer, "cho tôi gặp người");
            Handoff third = open(session, customer, "cho tôi gặp người");

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(third.id()).isEqualTo(first.id());
            assertThat(repository.queue(null, null, 50, 0)).hasSize(1);
            assertThat(openedCount()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("escalateWithTurn ghi lượt chat (câu hỏi + câu báo) rồi mới mở phiếu")
        void escalate_with_turn() {
            UUID session = UUID.randomUUID();

            Handoff opened = handoffs.escalateWithTurn(
                    session, customer, HandoffTrigger.LOW_CONFIDENCE, "hết vòng", "tôi cần giúp", "Đã chuyển nhé");

            assertThat(opened.sessionId()).isEqualTo(session);
            assertThat(history.transcript(session, customer, 10))
                    .extracting(ChatMessage::role, ChatMessage::content)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(ChatRole.USER, "tôi cần giúp"),
                            org.assertj.core.groups.Tuple.tuple(ChatRole.ASSISTANT, "Đã chuyển nhé"));
        }

        @Test
        @DisplayName("phiếu đang mở của phiên tra được qua openFor và openRowFor; phiên khác thì rỗng")
        void open_for() {
            UUID session = sessionWith(customer, "x");
            open(session, customer, "câu hỏi");

            assertThat(handoffs.openFor(session)).isPresent();
            assertThat(handoffs.openRowFor(session)).isPresent();
            assertThat(handoffs.openFor(UUID.randomUUID())).isEmpty();
            assertThat(handoffs.openRowFor(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    @DisplayName("khách tự bấm nút gặp nhân viên")
    class KhachBamNut {

        @Test
        @DisplayName("ý định lấy từ câu CUỐI của khách, bỏ qua câu của trợ lý")
        void y_dinh_tu_cau_cuoi_cua_khach() {
            UUID session = sessionWith(customer, "chào bạn", "tôi muốn hoàn vé đơn này");
            history.append(session, customer, ChatRole.ASSISTANT, "Bạn cho mình mã đơn nhé");

            HandoffViews.HandoffRow row = handoffs.requestByCustomer(session, customer);

            assertThat(row.intent()).isEqualTo("REFUND");
            assertThat(row.trigger()).isEqualTo("CUSTOMER_REQUEST");
            assertThat(row.lastQuestion()).isEqualTo("tôi muốn hoàn vé đơn này");
            assertThat(row.reason()).isEqualTo("Khách bấm nút gặp nhân viên");
        }

        @Test
        @DisplayName("phiên chưa có tin nhắn của khách thì phiếu GENERAL không có câu hỏi")
        void khong_co_tin_nhan() {
            UUID session = UUID.randomUUID();
            history.append(session, customer, ChatRole.ASSISTANT, "Xin chào, mình giúp được gì?");

            HandoffViews.HandoffRow row = handoffs.requestByCustomer(session, customer);

            assertThat(row.intent()).isEqualTo("GENERAL");
            assertThat(row.lastQuestion()).isNull();
        }

        @Test
        @DisplayName("phiên của người khác hoặc không tồn tại đều là CHAT_SESSION_NOT_FOUND và KHÔNG mở phiếu")
        void phien_khong_phai_cua_minh() {
            UUID session = sessionWith(customer, "tin của chủ phiên");
            UUID stranger = UUID.randomUUID();

            for (UUID target : List.of(session, UUID.randomUUID())) {
                assertThatThrownBy(() -> handoffs.requestByCustomer(target, stranger))
                        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                                .isEqualTo(AiChatboxErrorCode.CHAT_SESSION_NOT_FOUND));
            }
            assertThat(repository.queue(null, null, 50, 0)).isEmpty();
        }

        @Test
        @DisplayName("bấm hai lần trả cùng một phiếu")
        void bam_hai_lan() {
            UUID session = sessionWith(customer, "cho tôi hỏi");

            HandoffViews.HandoffRow first = handoffs.requestByCustomer(session, customer);
            HandoffViews.HandoffRow second = handoffs.requestByCustomer(session, customer);

            assertThat(second.id()).isEqualTo(first.id());
        }
    }

    @Nested
    @DisplayName("nhận phiếu")
    class NhanPhieu {

        @Test
        @DisplayName("nhận được thì trả cả hội thoại và hiện tên người nhận")
        void nhan_duoc() {
            UUID session = sessionWith(customer, "tôi cần giúp");
            identity.names.put(agentA, "Lê Thanh");
            Handoff opened = open(session, customer, "tôi cần giúp");
            clock.advance(Duration.ofSeconds(90));

            HandoffViews.HandoffThread thread = handoffs.claim(opened.id(), agentA, 50);

            assertThat(thread.handoff().status()).isEqualTo("ASSIGNED");
            assertThat(thread.handoff().assignedAgentName()).isEqualTo("Lê Thanh");
            assertThat(thread.messages())
                    .extracting(HandoffViews.MessageRow::content)
                    .contains("tôi cần giúp");
            // Thời gian CHỜ dừng ở lúc được nhận, không chạy tiếp.
            assertThat(thread.handoff().waitingSeconds()).isEqualTo(90);
            clock.advance(Duration.ofHours(5));
            assertThat(handoffs.thread(opened.id(), 50).handoff().waitingSeconds())
                    .isEqualTo(90);
        }

        @Test
        @DisplayName("chính người đang giữ bấm lại là idempotent, trả hội thoại chứ không 409")
        void nhan_lai_chinh_minh() {
            Handoff opened = open(sessionWith(customer, "x"), customer, "x");
            handoffs.claim(opened.id(), agentA, 50);

            HandoffViews.HandoffThread again = handoffs.claim(opened.id(), agentA, 50);

            assertThat(again.handoff().assignedAgentId()).isEqualTo(agentA);
        }

        @Test
        @DisplayName("người khác nhận trước thì HANDOFF_ALREADY_TAKEN, và phiếu vẫn của người đầu")
        void nguoi_khac_nhan_truoc() {
            Handoff opened = open(sessionWith(customer, "x"), customer, "x");
            handoffs.claim(opened.id(), agentA, 50);

            assertThatThrownBy(() -> handoffs.claim(opened.id(), agentB, 50))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(AiChatboxErrorCode.HANDOFF_ALREADY_TAKEN));
            assertThat(repository.findById(opened.id()).orElseThrow().assignedAgentId())
                    .isEqualTo(agentA);
        }

        @Test
        @DisplayName("phiếu đã đóng thì HANDOFF_ALREADY_RESOLVED, kể cả với chính người từng giữ")
        void phieu_da_dong() {
            Handoff opened = open(sessionWith(customer, "x"), customer, "x");
            handoffs.claim(opened.id(), agentA, 50);
            handoffs.resolve(opened.id(), agentA);

            for (UUID agent : List.of(agentA, agentB)) {
                assertThatThrownBy(() -> handoffs.claim(opened.id(), agent, 50))
                        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                                .isEqualTo(AiChatboxErrorCode.HANDOFF_ALREADY_RESOLVED));
            }
        }

        @Test
        @DisplayName("phiếu không tồn tại thì HANDOFF_NOT_FOUND")
        void khong_ton_tai() {
            assertThatThrownBy(() -> handoffs.claim(UUID.randomUUID(), agentA, 50))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(AiChatboxErrorCode.HANDOFF_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("trả lời và đóng phiếu")
    class TraLoiVaDong {

        @Test
        @DisplayName(
                "người trực đã nhận trả lời được; lời nhắn ghi dưới vai AGENT và thuộc về CHỦ PHIÊN, không phải người trực")
        void tra_loi() {
            UUID session = sessionWith(customer, "x");
            Handoff opened = open(session, customer, "x");
            handoffs.claim(opened.id(), agentA, 50);

            handoffs.reply(opened.id(), agentA, "Chào bạn, mình hỗ trợ nhé");

            ChatMessage last = history.transcript(session, customer, 10).getLast();
            assertThat(last.role()).isEqualTo(ChatRole.AGENT);
            assertThat(last.content()).isEqualTo("Chào bạn, mình hỗ trợ nhé");
            // Hội thoại vẫn của khách: người trực không đọc được nó bằng id của mình.
            assertThat(history.ownerOf(session)).contains(customer);
        }

        @Test
        @DisplayName("chưa nhận phiếu hoặc người khác đang giữ thì HANDOFF_NOT_ASSIGNED và không ghi gì")
        void chua_nhan() {
            UUID session = sessionWith(customer, "x");
            Handoff opened = open(session, customer, "x");
            int before = history.transcriptForSupport(session, 50).size();

            assertThatThrownBy(() -> handoffs.reply(opened.id(), agentA, "chen ngang"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(AiChatboxErrorCode.HANDOFF_NOT_ASSIGNED));
            handoffs.claim(opened.id(), agentA, 50);
            assertThatThrownBy(() -> handoffs.reply(opened.id(), agentB, "chen ngang"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(AiChatboxErrorCode.HANDOFF_NOT_ASSIGNED));

            assertThat(history.transcriptForSupport(session, 50)).hasSize(before);
        }

        @Test
        @DisplayName("phiếu đã đóng thì không trả lời thêm được (tránh hai phía cùng nói)")
        void phieu_da_dong() {
            UUID session = sessionWith(customer, "x");
            Handoff opened = open(session, customer, "x");
            handoffs.claim(opened.id(), agentA, 50);
            handoffs.resolve(opened.id(), agentA);

            assertThatThrownBy(() -> handoffs.reply(opened.id(), agentA, "muộn rồi"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(AiChatboxErrorCode.HANDOFF_ALREADY_RESOLVED));
        }

        @Test
        @DisplayName("phiếu không tồn tại thì HANDOFF_NOT_FOUND")
        void khong_ton_tai() {
            assertThatThrownBy(() -> handoffs.reply(UUID.randomUUID(), agentA, "x"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(AiChatboxErrorCode.HANDOFF_NOT_FOUND));
            assertThatThrownBy(() -> handoffs.resolve(UUID.randomUUID(), agentA))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(AiChatboxErrorCode.HANDOFF_NOT_FOUND));
        }

        @Test
        @DisplayName("đóng phiếu: người giữ đóng được, và đóng lần hai KHÔNG dịch mốc resolvedAt")
        void dong_phieu_idempotent() {
            Handoff opened = open(sessionWith(customer, "x"), customer, "x");
            handoffs.claim(opened.id(), agentA, 50);
            clock.advance(Duration.ofMinutes(5));

            HandoffViews.HandoffRow first = handoffs.resolve(opened.id(), agentA);
            clock.advance(Duration.ofHours(1));
            HandoffViews.HandoffRow second = handoffs.resolve(opened.id(), agentA);

            assertThat(first.status()).isEqualTo("RESOLVED");
            assertThat(first.resolvedAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
            assertThat(second.resolvedAt()).isEqualTo(first.resolvedAt());
        }

        @Test
        @DisplayName("không đóng được phiếu do người khác phụ trách (HANDOFF_NOT_ASSIGNED), phiếu vẫn mở")
        void dong_phieu_cua_nguoi_khac() {
            Handoff opened = open(sessionWith(customer, "x"), customer, "x");
            handoffs.claim(opened.id(), agentA, 50);

            assertThatThrownBy(() -> handoffs.resolve(opened.id(), agentB))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(AiChatboxErrorCode.HANDOFF_NOT_ASSIGNED));
            assertThat(repository.findById(opened.id()).orElseThrow().isOpen()).isTrue();
        }

        @Test
        @DisplayName("phiếu chưa ai nhận thì ai cũng đóng được (khách tự giải quyết, nhân viên dọn)")
        void dong_phieu_chua_ai_nhan() {
            Handoff opened = open(sessionWith(customer, "x"), customer, "x");

            HandoffViews.HandoffRow row = handoffs.resolve(opened.id(), agentB);

            assertThat(row.status()).isEqualTo("RESOLVED");
        }

        @Test
        @DisplayName("đóng phiếu thì lượt sau trợ lý trả lời trở lại: openFor rỗng")
        void dong_thi_tro_ly_tra_loi_lai() {
            UUID session = sessionWith(customer, "x");
            Handoff opened = open(session, customer, "x");

            handoffs.resolve(opened.id(), agentA);

            assertThat(handoffs.openFor(session)).isEmpty();
        }
    }

    @Nested
    @DisplayName("hàng đợi và tra cứu")
    class HangDoiVaTraCuu {

        private List<HandoffViews.HandoffRow> find(String status, String intent, String query) {
            return handoffs.search((UUID) null, status, intent, query, 50, 0);
        }

        private Handoff openAt(Duration after, SupportIntent intent, String reason, String question) {
            clock.advance(after);
            return handoffs.escalate(
                    sessionWith(UUID.randomUUID(), "x"),
                    UUID.randomUUID(),
                    HandoffTrigger.CUSTOMER_REQUEST,
                    intent,
                    reason,
                    null,
                    question);
        }

        @Test
        @DisplayName(
                "hàng đợi cũ nhất trước, lọc theo ý định bằng TÊN (không phân biệt hoa thường), tên lạ thì không lọc")
        void hang_doi_va_loc_y_dinh() {
            Handoff refund = openAt(Duration.ZERO, SupportIntent.REFUND, "r", "q1");
            Handoff incident = openAt(Duration.ofMinutes(1), SupportIntent.INCIDENT, "i", "q2");

            assertThat(handoffs.queue(null, null, 50, 0))
                    .extracting(HandoffViews.HandoffRow::id)
                    .containsExactly(refund.id(), incident.id());
            assertThat(handoffs.queue(null, "incident", 50, 0))
                    .extracting(HandoffViews.HandoffRow::id)
                    .containsExactly(incident.id());
            assertThat(handoffs.queue(null, "  REFUND ", 50, 0)).hasSize(1);
            assertThat(handoffs.queue(null, "khong-co", 50, 0)).hasSize(2);
            assertThat(handoffs.queue(null, "", 50, 0)).hasSize(2);
        }

        @Test
        @DisplayName("lọc 'của tôi' chỉ lấy phiếu người ấy đang giữ")
        void loc_cua_toi() {
            Handoff mine = openAt(Duration.ZERO, SupportIntent.GENERAL, "r", "q");
            openAt(Duration.ofMinutes(1), SupportIntent.GENERAL, "r", "q");
            handoffs.claim(mine.id(), agentA, 10);

            assertThat(handoffs.queue(agentA, null, 50, 0))
                    .extracting(HandoffViews.HandoffRow::id)
                    .containsExactly(mine.id());
            assertThat(handoffs.queue(agentB, null, 50, 0)).isEmpty();
        }

        @Test
        @DisplayName("phân trang theo limit và offset")
        void phan_trang() {
            for (int i = 0; i < 5; i++) {
                openAt(Duration.ofMinutes(1), SupportIntent.GENERAL, "r" + i, "q");
            }

            assertThat(handoffs.queue(null, null, 2, 0))
                    .extracting(HandoffViews.HandoffRow::reason)
                    .containsExactly("r0", "r1");
            assertThat(handoffs.queue(null, null, 2, 4))
                    .extracting(HandoffViews.HandoffRow::reason)
                    .containsExactly("r4");
        }

        @Test
        @DisplayName(
                "tra cứu: mới nhất trước, trạng thái theo tên (OPEN gồm WAITING và ASSIGNED), tên lạ thì không lọc")
        void tra_cuu_theo_trang_thai() {
            Handoff waiting = openAt(Duration.ZERO, SupportIntent.GENERAL, "cho", "q");
            Handoff assigned = openAt(Duration.ofMinutes(1), SupportIntent.GENERAL, "giu", "q");
            Handoff resolved = openAt(Duration.ofMinutes(2), SupportIntent.GENERAL, "xong", "q");
            handoffs.claim(assigned.id(), agentA, 10);
            handoffs.resolve(resolved.id(), agentA);

            assertThat(find(null, null, null))
                    .extracting(HandoffViews.HandoffRow::id)
                    .containsExactly(resolved.id(), assigned.id(), waiting.id());
            assertThat(find("open", null, null))
                    .extracting(HandoffViews.HandoffRow::id)
                    .containsExactly(assigned.id(), waiting.id());
            assertThat(find("WAITING", null, null))
                    .extracting(HandoffViews.HandoffRow::id)
                    .containsExactly(waiting.id());
            assertThat(find("assigned", null, null))
                    .extracting(HandoffViews.HandoffRow::id)
                    .containsExactly(assigned.id());
            assertThat(find(" resolved ", null, null))
                    .extracting(HandoffViews.HandoffRow::id)
                    .containsExactly(resolved.id());
            assertThat(find("linh-tinh", null, null)).hasSize(3);
            assertThat(find("  ", null, null)).hasSize(3);
        }

        @Test
        @DisplayName("tra cứu theo chữ tìm trong lý do và câu hỏi cuối, kết hợp được với bộ lọc khác")
        void tra_cuu_theo_chu() {
            openAt(Duration.ZERO, SupportIntent.REFUND, "Khách đòi hoàn tiền", "tôi muốn hoàn vé");
            openAt(Duration.ofMinutes(1), SupportIntent.INCIDENT, "QR lỗi", "mã QR không quét được");

            assertThat(find(null, null, "QR")).hasSize(1);
            assertThat(find(null, null, "hoàn")).hasSize(1);
            assertThat(find(null, "refund", "hoàn")).hasSize(1);
            assertThat(find(null, "incident", "hoàn")).isEmpty();
        }

        @Test
        @DisplayName("tên người trực và thông tin khách được tra MỘT lần cho mỗi người dù có bao nhiêu dòng")
        void tra_mot_lan_moi_nguoi() {
            UUID sameCustomer = UUID.randomUUID();
            identity.names.put(agentA, "Lê Thanh");
            identity.names.put(sameCustomer, "Khách Một");
            identity.emails.put(sameCustomer, "khach@x.vn");
            for (int i = 0; i < 4; i++) {
                clock.advance(Duration.ofMinutes(1));
                Handoff opened = handoffs.escalate(
                        sessionWith(sameCustomer, "x"),
                        sameCustomer,
                        HandoffTrigger.CUSTOMER_REQUEST,
                        SupportIntent.GENERAL,
                        "r",
                        null,
                        "q");
                handoffs.claim(opened.id(), agentA, 10);
            }
            identity.nameLookups = 0;
            identity.contactLookups = 0;

            List<HandoffViews.HandoffRow> rows = handoffs.queue(null, null, 50, 0);

            assertThat(rows).hasSize(4);
            assertThat(identity.contactLookups).isEqualTo(1);
            assertThat(identity.nameLookups).isEqualTo(1);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.assignedAgentName()).isEqualTo("Lê Thanh");
                assertThat(row.customerName()).isEqualTo("Khách Một");
                assertThat(row.customerEmail()).isEqualTo("khach@x.vn");
            });
        }

        @Test
        @DisplayName(
                "không tra được tên (identity hỏng) thì dòng vẫn hiện, chỉ thiếu tên; lần tra rỗng cũng được nhớ trong danh sách")
        void khong_tra_duoc_ten() {
            for (int i = 0; i < 3; i++) {
                clock.advance(Duration.ofMinutes(1));
                Handoff opened = handoffs.escalate(
                        sessionWith(UUID.randomUUID(), "x"),
                        UUID.randomUUID(),
                        HandoffTrigger.CUSTOMER_REQUEST,
                        SupportIntent.GENERAL,
                        "r",
                        null,
                        "q");
                handoffs.claim(opened.id(), agentB, 10);
            }
            identity.nameLookups = 0;

            List<HandoffViews.HandoffRow> rows = handoffs.queue(null, null, 50, 0);

            assertThat(rows).hasSize(3).allSatisfy(row -> {
                assertThat(row.assignedAgentName()).isNull();
                assertThat(row.customerName()).isNull();
            });
            assertThat(identity.nameLookups).isEqualTo(1);
        }

        @Test
        @DisplayName("phiếu chưa có người nhận không gọi identity để tra tên người trực")
        void khong_tra_ten_khi_chua_co_nguoi_nhan() {
            open(sessionWith(customer, "x"), customer, "x");
            identity.nameLookups = 0;

            handoffs.queue(null, null, 50, 0);

            assertThat(identity.nameLookups).isZero();
        }
    }

    @Nested
    @DisplayName("màn hình người trực và màn hình khách")
    class ManHinh {

        @Test
        @DisplayName("phiếu sự cố kèm checklist theo loại sự cố ghi trong details")
        void checklist_theo_loai_su_co() {
            UUID session = sessionWith(customer, "x");
            Handoff opened = handoffs.escalate(
                    session,
                    customer,
                    HandoffTrigger.LOW_CONFIDENCE,
                    SupportIntent.INCIDENT,
                    "[Sự cố] QR",
                    "{\"kind\":\"QR_NOT_SCANNABLE\",\"orderNumber\":\"NT-1\"}",
                    "QR không quét được");

            HandoffViews.HandoffThread thread = handoffs.thread(opened.id(), 50);

            assertThat(thread.checklist()).isNotEmpty();
            assertThat(thread.checklist())
                    .isEqualTo(com.nexaticket.aichatbox.application.handoff.IncidentTemplates.forKind(
                                    com.nexaticket.aichatbox.domain.model.IncidentKind.QR_NOT_SCANNABLE)
                            .checklist());
        }

        @Test
        @DisplayName("không có checklist khi không phải sự cố, khi details thiếu hoặc không nhận ra loại")
        void khong_co_checklist() {
            UUID s1 = sessionWith(customer, "x");
            Handoff general = handoffs.escalate(
                    s1,
                    customer,
                    HandoffTrigger.CUSTOMER_REQUEST,
                    SupportIntent.REFUND,
                    "r",
                    "{\"kind\":\"OTHER\"}",
                    "q");
            UUID s2 = sessionWith(customer, "x");
            Handoff noDetails = handoffs.escalate(
                    s2, customer, HandoffTrigger.CUSTOMER_REQUEST, SupportIntent.INCIDENT, "r", null, "q");
            UUID s3 = sessionWith(customer, "x");
            Handoff unknownKind = handoffs.escalate(
                    s3, customer, HandoffTrigger.CUSTOMER_REQUEST, SupportIntent.INCIDENT, "r", "không phải json", "q");

            assertThat(handoffs.thread(general.id(), 50).checklist()).isEmpty();
            assertThat(handoffs.thread(noDetails.id(), 50).checklist()).isEmpty();
            assertThat(handoffs.thread(unknownKind.id(), 50).checklist()).isEmpty();
        }

        @Test
        @DisplayName("thread chỉ lấy tối đa transcriptLimit tin gần nhất")
        void gioi_han_transcript() {
            UUID session = sessionWith(customer, "m1", "m2", "m3", "m4");
            Handoff opened = open(session, customer, "m4");

            HandoffViews.HandoffThread thread = handoffs.thread(opened.id(), 2);

            assertThat(thread.messages())
                    .extracting(HandoffViews.MessageRow::content)
                    .containsExactly("m3", "m4");
        }

        @Test
        @DisplayName("customerThread: hội thoại của chính khách, handoff null khi trợ lý đang trả lời")
        void customer_thread_khong_co_phieu() {
            UUID session = sessionWith(customer, "tin một");
            history.append(session, customer, ChatRole.ASSISTANT, "trả lời một");

            HandoffViews.ChatThread thread = handoffs.customerThread(session, customer, 50);

            assertThat(thread.sessionId()).isEqualTo(session);
            assertThat(thread.handoff()).isNull();
            assertThat(thread.messages())
                    .extracting(HandoffViews.MessageRow::role, HandoffViews.MessageRow::content)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("USER", "tin một"),
                            org.assertj.core.groups.Tuple.tuple("ASSISTANT", "trả lời một"));
        }

        @Test
        @DisplayName("customerThread: có phiếu mở thì kèm phiếu, để giao diện đổi sang 'đang chờ nhân viên'")
        void customer_thread_co_phieu() {
            UUID session = sessionWith(customer, "x");
            open(session, customer, "x");

            HandoffViews.ChatThread thread = handoffs.customerThread(session, customer, 50);

            assertThat(thread.handoff()).isNotNull();
            assertThat(thread.handoff().status()).isEqualTo("WAITING");
        }

        @Test
        @DisplayName("customerThread: phiên của người khác là CHAT_SESSION_NOT_FOUND, không lộ nội dung")
        void customer_thread_phien_nguoi_khac() {
            UUID session = sessionWith(customer, "bí mật");

            assertThatThrownBy(() -> handoffs.customerThread(session, UUID.randomUUID(), 50))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(AiChatboxErrorCode.CHAT_SESSION_NOT_FOUND));
        }

        @Test
        @DisplayName("HandoffRow.of: thời gian chờ chạy theo đồng hồ khi chưa ai nhận")
        void thoi_gian_cho() {
            Handoff opened = open(sessionWith(customer, "x"), customer, "x");

            HandoffViews.HandoffRow row = HandoffViews.HandoffRow.of(opened, T0.plusSeconds(125));

            assertThat(row.waitingSeconds()).isEqualTo(125);
            assertThat(HandoffViews.HandoffRow.of(opened, T0.plusSeconds(10), "Tên")
                            .assignedAgentName())
                    .isEqualTo("Tên");
            assertThat(Set.of(row.status())).containsExactly("WAITING");
        }
    }
}
