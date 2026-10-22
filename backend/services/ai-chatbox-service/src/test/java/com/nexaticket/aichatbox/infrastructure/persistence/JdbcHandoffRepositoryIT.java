// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.domain.model.ChatRole;
import com.nexaticket.aichatbox.domain.model.Handoff;
import com.nexaticket.aichatbox.domain.model.HandoffStatus;
import com.nexaticket.aichatbox.domain.model.HandoffTrigger;
import com.nexaticket.aichatbox.domain.model.SupportIntent;
import com.nexaticket.aichatbox.domain.port.ChatHistoryPort;
import com.nexaticket.aichatbox.domain.port.HandoffRepository;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Kho phiếu chuyển tiếp trên PostgreSQL thật: unique index bộ phận, UPDATE có điều kiện, các ràng
 * buộc CHECK và thứ tự truy vấn đều nằm trong SQL, nên mock không kiểm được chúng.
 *
 * <p>Mọi UUID sinh mới mỗi lần chạy và {@code AiChatboxTestBase} dọn bảng trước mỗi ca, nên container
 * dùng lại giữa các lần chạy không làm test phụ thuộc dữ liệu cũ.
 */
@DisplayName("JdbcHandoffRepository")
class JdbcHandoffRepositoryIT extends AiChatboxTestBase {

    /** Mốc cố định, cắt về micro-giây — độ chính xác của TIMESTAMPTZ. */
    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");

    @Autowired
    HandoffRepository repository;

    @Autowired
    ChatHistoryPort history;

    /** Tạo phiên (phiếu có khoá ngoại tới chat_sessions) và trả id. */
    private UUID newSession(UUID owner) {
        UUID session = UUID.randomUUID();
        history.append(session, owner, ChatRole.USER, "tin đầu tiên");
        return session;
    }

    private Handoff candidate(
            UUID session, UUID owner, SupportIntent intent, String reason, String question, Instant at) {
        return Handoff.request(session, owner, HandoffTrigger.CUSTOMER_REQUEST, intent, reason, null, question, at);
    }

    private Handoff openNew(SupportIntent intent, String reason, String question, Instant at) {
        UUID owner = UUID.randomUUID();
        return repository.openOrExisting(candidate(newSession(owner), owner, intent, reason, question, at));
    }

    @Nested
    @DisplayName("openOrExisting")
    class OpenOrExisting {

        @Test
        @DisplayName("lưu và đọc lại đủ mọi cột, kể cả cột null và mốc thời gian")
        void luu_va_doc_lai() {
            UUID owner = UUID.randomUUID();
            UUID session = newSession(owner);
            Handoff candidate = Handoff.request(
                    session,
                    owner,
                    HandoffTrigger.ASSISTANT_UNAVAILABLE,
                    SupportIntent.INCIDENT,
                    "[Sự cố] QR không quét được",
                    "{\"kind\":\"QR_NOT_SCANNABLE\"}",
                    "mã QR lỗi",
                    T0.plusNanos(123_000));

            Handoff saved = repository.openOrExisting(candidate);
            Handoff loaded = repository.findById(candidate.id()).orElseThrow();

            assertThat(saved).isEqualTo(candidate);
            assertThat(loaded.id()).isEqualTo(candidate.id());
            assertThat(loaded.sessionId()).isEqualTo(session);
            assertThat(loaded.userId()).isEqualTo(owner);
            assertThat(loaded.status()).isEqualTo(HandoffStatus.WAITING);
            assertThat(loaded.trigger()).isEqualTo(HandoffTrigger.ASSISTANT_UNAVAILABLE);
            assertThat(loaded.intent()).isEqualTo(SupportIntent.INCIDENT);
            assertThat(loaded.reason()).isEqualTo("[Sự cố] QR không quét được");
            assertThat(loaded.details()).contains("QR_NOT_SCANNABLE");
            assertThat(loaded.lastQuestion()).isEqualTo("mã QR lỗi");
            assertThat(loaded.requestedAt()).isEqualTo(T0.plusNanos(123_000));
            assertThat(loaded.assignedAgentId()).isNull();
            assertThat(loaded.assignedAt()).isNull();
            assertThat(loaded.resolvedAt()).isNull();
        }

        @Test
        @DisplayName("details và câu hỏi null được lưu là NULL")
        void cot_null() {
            UUID owner = UUID.randomUUID();
            Handoff candidate = candidate(newSession(owner), owner, SupportIntent.GENERAL, "r", null, T0);

            repository.openOrExisting(candidate);
            Handoff loaded = repository.findById(candidate.id()).orElseThrow();

            assertThat(loaded.details()).isNull();
            assertThat(loaded.lastQuestion()).isNull();
        }

        @Test
        @DisplayName("phiên đã có phiếu mở thì trả lại CHÍNH phiếu đó, không tạo phiếu thứ hai")
        void idempotent_theo_phien() {
            UUID owner = UUID.randomUUID();
            UUID session = newSession(owner);
            Handoff first = repository.openOrExisting(candidate(session, owner, SupportIntent.GENERAL, "một", "q", T0));

            Handoff again = repository.openOrExisting(
                    candidate(session, owner, SupportIntent.REFUND, "hai", "q2", T0.plusSeconds(60)));

            assertThat(again.id()).isEqualTo(first.id());
            assertThat(again.reason()).isEqualTo("một");
            assertThat(repository.queue(null, null, 50, 0)).hasSize(1);
        }

        @Test
        @DisplayName("phiếu đã đóng thì phiên mở được phiếu MỚI")
        void sau_khi_dong_mo_duoc_phieu_moi() {
            UUID owner = UUID.randomUUID();
            UUID session = newSession(owner);
            Handoff first = repository.openOrExisting(candidate(session, owner, SupportIntent.GENERAL, "một", "q", T0));
            persist(first.resolve(T0.plusSeconds(30)));

            Handoff second = repository.openOrExisting(
                    candidate(session, owner, SupportIntent.GENERAL, "hai", "q", T0.plusSeconds(60)));

            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(repository.openBySession(session)).map(Handoff::id).contains(second.id());
            assertThat(repository.search(null, Set.of(), null, null, true, 50, 0))
                    .hasSize(2);
        }

        @Test
        @DisplayName("hai request cùng lúc cho cùng một phiên chỉ ra MỘT phiếu và không ai nhận lỗi")
        void dong_thoi_chi_ra_mot_phieu() throws Exception {
            UUID owner = UUID.randomUUID();
            UUID session = newSession(owner);
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            try {
                List<Future<UUID>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    int n = i;
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return repository
                                .openOrExisting(candidate(session, owner, SupportIntent.GENERAL, "r" + n, "q", T0))
                                .id();
                    }));
                }
                ready.await();
                go.countDown();

                Set<UUID> ids = new java.util.HashSet<>();
                for (Future<UUID> result : results) {
                    ids.add(result.get());
                }

                assertThat(ids).hasSize(1);
                assertThat(repository.queue(null, null, 50, 0)).hasSize(1);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("phiên không tồn tại bị khoá ngoại chặn — đó là lý do lượt chat phải được ghi TRƯỚC phiếu")
        void khoa_ngoai_chan_phien_khong_ton_tai() {
            UUID owner = UUID.randomUUID();
            Handoff orphan = candidate(UUID.randomUUID(), owner, SupportIntent.GENERAL, "r", "q", T0);

            assertThatThrownBy(() -> repository.openOrExisting(orphan))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("truy vấn")
    class TruyVan {

        @Test
        @DisplayName("openBySession và findById trả rỗng khi không có; phiếu đã đóng không còn là 'đang mở'")
        void khong_co() {
            assertThat(repository.openBySession(UUID.randomUUID())).isEmpty();
            assertThat(repository.findById(UUID.randomUUID())).isEmpty();

            Handoff opened = openNew(SupportIntent.GENERAL, "r", "q", T0);
            persist(opened.resolve(T0.plusSeconds(1)));

            assertThat(repository.openBySession(opened.sessionId())).isEmpty();
            assertThat(repository.findById(opened.id())).isPresent();
        }

        @Test
        @DisplayName("queue: cũ nhất trước, bỏ phiếu RESOLVED, phân trang đúng")
        void queue_fifo_va_phan_trang() {
            Handoff a = openNew(SupportIntent.GENERAL, "a", "q", T0);
            Handoff c = openNew(SupportIntent.GENERAL, "c", "q", T0.plusSeconds(120));
            Handoff b = openNew(SupportIntent.GENERAL, "b", "q", T0.plusSeconds(60));
            Handoff done = openNew(SupportIntent.GENERAL, "xong", "q", T0.plusSeconds(10));
            persist(done.resolve(T0.plusSeconds(20)));

            assertThat(repository.queue(null, null, 50, 0))
                    .extracting(Handoff::id)
                    .containsExactly(a.id(), b.id(), c.id());
            assertThat(repository.queue(null, null, 1, 1))
                    .extracting(Handoff::id)
                    .containsExactly(b.id());
            assertThat(repository.queue(null, null, 50, 3)).isEmpty();
        }

        @Test
        @DisplayName("queue lọc theo ý định và theo người đang giữ")
        void queue_loc() {
            Handoff refund = openNew(SupportIntent.REFUND, "r", "q", T0);
            Handoff incident = openNew(SupportIntent.INCIDENT, "i", "q", T0.plusSeconds(1));
            UUID agent = UUID.randomUUID();
            repository.claim(incident.id(), agent, T0.plusSeconds(5));

            assertThat(repository.queue(null, SupportIntent.REFUND, 50, 0))
                    .extracting(Handoff::id)
                    .containsExactly(refund.id());
            assertThat(repository.queue(agent, null, 50, 0))
                    .extracting(Handoff::id)
                    .containsExactly(incident.id());
            assertThat(repository.queue(agent, SupportIntent.REFUND, 50, 0)).isEmpty();
            assertThat(repository.queue(UUID.randomUUID(), null, 50, 0)).isEmpty();
        }

        @Test
        @DisplayName("search: lọc theo tập trạng thái, rỗng nghĩa là tất cả")
        void search_theo_trang_thai() {
            Handoff waiting = openNew(SupportIntent.GENERAL, "cho", "q", T0);
            Handoff assigned = openNew(SupportIntent.GENERAL, "giu", "q", T0.plusSeconds(1));
            Handoff resolved = openNew(SupportIntent.GENERAL, "xong", "q", T0.plusSeconds(2));
            repository.claim(assigned.id(), UUID.randomUUID(), T0.plusSeconds(3));
            persist(resolved.resolve(T0.plusSeconds(4)));

            assertThat(ids(repository.search(null, Set.of(HandoffStatus.WAITING), null, null, true, 50, 0)))
                    .containsExactly(waiting.id());
            assertThat(ids(repository.search(
                            null, Set.of(HandoffStatus.WAITING, HandoffStatus.ASSIGNED), null, null, false, 50, 0)))
                    .containsExactly(waiting.id(), assigned.id());
            assertThat(ids(repository.search(null, Set.of(), null, null, true, 50, 0)))
                    .containsExactly(resolved.id(), assigned.id(), waiting.id());
            assertThat(ids(repository.search(null, null, null, null, false, 50, 0)))
                    .containsExactly(waiting.id(), assigned.id(), resolved.id());
        }

        @Test
        @DisplayName("search: lọc theo ý định, theo người giữ, và newestFirst đảo thứ tự")
        void search_y_dinh_va_thu_tu() {
            Handoff r1 = openNew(SupportIntent.REFUND, "r1", "q", T0);
            Handoff r2 = openNew(SupportIntent.REFUND, "r2", "q", T0.plusSeconds(1));
            openNew(SupportIntent.INCIDENT, "i", "q", T0.plusSeconds(2));
            UUID agent = UUID.randomUUID();
            repository.claim(r1.id(), agent, T0.plusSeconds(5));

            assertThat(ids(repository.search(null, Set.of(), SupportIntent.REFUND, null, true, 50, 0)))
                    .containsExactly(r2.id(), r1.id());
            assertThat(ids(repository.search(null, Set.of(), SupportIntent.REFUND, null, false, 50, 0)))
                    .containsExactly(r1.id(), r2.id());
            assertThat(ids(repository.search(agent, Set.of(), null, null, true, 50, 0)))
                    .containsExactly(r1.id());
            assertThat(ids(repository.search(null, Set.of(), null, null, true, 1, 1)))
                    .hasSize(1);
        }

        @Test
        @DisplayName(
                "search theo chữ: ILIKE không phân biệt hoa thường, tìm cả lý do lẫn câu hỏi, bỏ qua khoảng trắng hai đầu")
        void search_theo_chu() {
            Handoff byReason = openNew(SupportIntent.GENERAL, "Khách đòi HOÀN TIỀN", "xin chào", T0);
            Handoff byQuestion =
                    openNew(SupportIntent.GENERAL, "lý do khác", "vé của tôi bị Lỗi QR", T0.plusSeconds(1));
            openNew(SupportIntent.GENERAL, "lý do khác nữa", null, T0.plusSeconds(2));

            assertThat(ids(repository.search(null, Set.of(), null, "hoàn tiền", true, 50, 0)))
                    .containsExactly(byReason.id());
            assertThat(ids(repository.search(null, Set.of(), null, "  lỗi qr ", true, 50, 0)))
                    .containsExactly(byQuestion.id());
            assertThat(repository.search(null, Set.of(), null, "không có đâu", true, 50, 0))
                    .isEmpty();
            // Rỗng hoặc chỉ khoảng trắng thì không lọc theo chữ.
            assertThat(repository.search(null, Set.of(), null, "   ", true, 50, 0))
                    .hasSize(3);
            assertThat(repository.search(null, Set.of(), null, null, true, 50, 0))
                    .hasSize(3);
        }

        @Test
        @DisplayName("search kết hợp mọi bộ lọc cùng lúc")
        void search_ket_hop() {
            Handoff target = openNew(SupportIntent.REFUND, "hoàn tiền gấp", "q", T0);
            openNew(SupportIntent.REFUND, "hoàn tiền gấp", "q", T0.plusSeconds(1));
            openNew(SupportIntent.INCIDENT, "hoàn tiền gấp", "q", T0.plusSeconds(2));
            UUID agent = UUID.randomUUID();
            repository.claim(target.id(), agent, T0.plusSeconds(5));

            assertThat(ids(repository.search(
                            agent, Set.of(HandoffStatus.ASSIGNED), SupportIntent.REFUND, "gấp", true, 50, 0)))
                    .containsExactly(target.id());
        }

        private List<UUID> ids(List<Handoff> handoffs) {
            return handoffs.stream().map(Handoff::id).toList();
        }
    }

    @Nested
    @DisplayName("claim")
    class Claim {

        @Test
        @DisplayName("nhận phiếu WAITING: chuyển ASSIGNED, ghi người nhận và mốc thời gian")
        void nhan_duoc() {
            Handoff opened = openNew(SupportIntent.GENERAL, "r", "q", T0);
            UUID agent = UUID.randomUUID();

            Optional<Handoff> claimed = repository.claim(opened.id(), agent, T0.plusSeconds(42));

            assertThat(claimed).isPresent();
            assertThat(claimed.get().status()).isEqualTo(HandoffStatus.ASSIGNED);
            assertThat(claimed.get().assignedAgentId()).isEqualTo(agent);
            assertThat(claimed.get().assignedAt()).isEqualTo(T0.plusSeconds(42));
            assertThat(claimed.get().requestedAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("người thứ hai nhận phiếu đã có người thì nhận về rỗng và không ghi đè người đầu")
        void nguoi_thu_hai_khong_ghi_de() {
            Handoff opened = openNew(SupportIntent.GENERAL, "r", "q", T0);
            UUID first = UUID.randomUUID();
            repository.claim(opened.id(), first, T0.plusSeconds(1));

            Optional<Handoff> second = repository.claim(opened.id(), UUID.randomUUID(), T0.plusSeconds(2));

            assertThat(second).isEmpty();
            Handoff current = repository.findById(opened.id()).orElseThrow();
            assertThat(current.assignedAgentId()).isEqualTo(first);
            assertThat(current.assignedAt()).isEqualTo(T0.plusSeconds(1));
        }

        @Test
        @DisplayName("không nhận được phiếu đã đóng hoặc phiếu không tồn tại")
        void khong_nhan_duoc() {
            Handoff opened = openNew(SupportIntent.GENERAL, "r", "q", T0);
            persist(opened.resolve(T0.plusSeconds(1)));

            assertThat(repository.claim(opened.id(), UUID.randomUUID(), T0.plusSeconds(2)))
                    .isEmpty();
            assertThat(repository.claim(UUID.randomUUID(), UUID.randomUUID(), T0))
                    .isEmpty();
        }

        @Test
        @DisplayName("nhiều người bấm Nhận cùng lúc: đúng MỘT người thắng")
        void dong_thoi_dung_mot_nguoi_thang() throws Exception {
            Handoff opened = openNew(SupportIntent.GENERAL, "r", "q", T0);
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            try {
                List<UUID> agents = new ArrayList<>();
                List<Future<Optional<Handoff>>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    UUID agent = UUID.randomUUID();
                    agents.add(agent);
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return repository.claim(opened.id(), agent, T0.plusSeconds(1));
                    }));
                }
                ready.await();
                go.countDown();

                int winners = 0;
                UUID winner = null;
                for (int i = 0; i < threads; i++) {
                    Optional<Handoff> result = results.get(i).get();
                    if (result.isPresent()) {
                        winners++;
                        winner = agents.get(i);
                    }
                }

                assertThat(winners).isEqualTo(1);
                assertThat(repository.findById(opened.id()).orElseThrow().assignedAgentId())
                        .isEqualTo(winner);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("transition")
    class Save {

        @Test
        @DisplayName("lưu phiếu đã nhận rồi đã đóng: trạng thái và các mốc thời gian đi theo")
        void luu_nhan_roi_dong() {
            Handoff opened = openNew(SupportIntent.GENERAL, "r", "q", T0);
            UUID agent = UUID.randomUUID();
            Handoff assigned = opened.assignTo(agent, T0.plusSeconds(10));
            persist(assigned);
            assertThat(repository.findById(opened.id()).orElseThrow().status()).isEqualTo(HandoffStatus.ASSIGNED);

            persist(assigned.resolve(T0.plusSeconds(99)));

            Handoff loaded = repository.findById(opened.id()).orElseThrow();
            assertThat(loaded.status()).isEqualTo(HandoffStatus.RESOLVED);
            assertThat(loaded.assignedAgentId()).isEqualTo(agent);
            assertThat(loaded.assignedAt()).isEqualTo(T0.plusSeconds(10));
            assertThat(loaded.resolvedAt()).isEqualTo(T0.plusSeconds(99));
        }

        @Test
        @DisplayName("đóng thẳng một phiếu chưa ai nhận là hợp lệ (phiếu rác, khách tự giải quyết)")
        void dong_thang_phieu_chua_nhan() {
            Handoff opened = openNew(SupportIntent.GENERAL, "r", "q", T0);

            persist(opened.resolve(T0.plusSeconds(5)));

            Handoff loaded = repository.findById(opened.id()).orElseThrow();
            assertThat(loaded.status()).isEqualTo(HandoffStatus.RESOLVED);
            assertThat(loaded.assignedAgentId()).isNull();
            assertThat(loaded.assignedAt()).isNull();
        }

        @Test
        @DisplayName("trạng thái và mốc thời gian lệch nhau bị CHECK của database chặn")
        void check_chan_trang_thai_lech() {
            Handoff opened = openNew(SupportIntent.GENERAL, "r", "q", T0);
            Handoff inconsistent = new Handoff(
                    opened.id(),
                    opened.sessionId(),
                    opened.userId(),
                    HandoffStatus.ASSIGNED,
                    opened.trigger(),
                    opened.intent(),
                    opened.reason(),
                    opened.details(),
                    opened.lastQuestion(),
                    null,
                    opened.requestedAt(),
                    null,
                    null);

            assertThatThrownBy(() -> persist(inconsistent)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("closeAbandoned")
    class CloseAbandoned {

        @Test
        @DisplayName("chỉ đóng phiếu WAITING cũ hơn mốc, ghi lý do vào reason và trả đúng số phiếu đã đóng")
        void dong_phieu_mo_coi() {
            Instant cutoff = T0.plus(1, ChronoUnit.HOURS);
            Handoff old = openNew(SupportIntent.GENERAL, "cũ", "q", cutoff.minusSeconds(1));
            Handoff exactlyAtCutoff = openNew(SupportIntent.GENERAL, "đúng mốc", "q", cutoff);
            Handoff fresh = openNew(SupportIntent.GENERAL, "mới", "q", cutoff.plusSeconds(1));
            Handoff takenButOld = openNew(SupportIntent.GENERAL, "có người giữ", "q", cutoff.minus(Duration.ofDays(3)));
            repository.claim(takenButOld.id(), UUID.randomUUID(), cutoff.minus(Duration.ofDays(2)));
            Instant now = cutoff.plusSeconds(500);

            int closed = repository.closeAbandoned(cutoff, now);

            assertThat(closed).isEqualTo(1);
            Handoff closedOld = repository.findById(old.id()).orElseThrow();
            assertThat(closedOld.status()).isEqualTo(HandoffStatus.RESOLVED);
            assertThat(closedOld.resolvedAt()).isEqualTo(now);
            assertThat(closedOld.reason()).isEqualTo("cũ [tự đóng: quá hạn chờ]");
            assertThat(repository.findById(exactlyAtCutoff.id()).orElseThrow().status())
                    .isEqualTo(HandoffStatus.WAITING);
            assertThat(repository.findById(fresh.id()).orElseThrow().status()).isEqualTo(HandoffStatus.WAITING);
            // Phiếu đã có người nhận là việc của người ấy, dù họ chậm.
            assertThat(repository.findById(takenButOld.id()).orElseThrow().status())
                    .isEqualTo(HandoffStatus.ASSIGNED);
        }

        @Test
        @DisplayName("không có phiếu nào quá hạn thì trả 0 và chạy lại là idempotent")
        void khong_co_gi_de_dong() {
            openNew(SupportIntent.GENERAL, "mới", "q", T0);

            assertThat(repository.closeAbandoned(T0.minusSeconds(1), T0)).isZero();

            Instant later = T0.plus(Duration.ofDays(2));
            assertThat(repository.closeAbandoned(later, later)).isEqualTo(1);
            assertThat(repository.closeAbandoned(later, later)).isZero();
        }

        @Test
        @DisplayName("sau khi phiếu mồ côi bị đóng, phiên mở lại được phiếu mới")
        void mo_lai_sau_khi_dong() {
            UUID owner = UUID.randomUUID();
            UUID session = newSession(owner);
            repository.openOrExisting(candidate(session, owner, SupportIntent.GENERAL, "r", "q", T0));
            repository.closeAbandoned(T0.plusSeconds(1), T0.plusSeconds(2));

            assertThat(repository.openBySession(session)).isEmpty();
            Handoff reopened = repository.openOrExisting(
                    candidate(session, owner, SupportIntent.GENERAL, "r2", "q", T0.plusSeconds(3)));
            assertThat(reopened.reason()).isEqualTo("r2");
        }
    }

    /**
     * Ghi trạng thái mới của phiếu qua {@code transition}. Epic 6 bỏ {@code save} (ghi mù, thua
     * cuộc đua); mọi chuyển trạng thái giờ phải nêu trạng thái và người nhận KỲ VỌNG. Ở đây lấy
     * chính giá trị hiện có trong DB làm kỳ vọng để các ca không đua nhau vẫn đọc như cũ.
     */
    private boolean persist(Handoff next) {
        Handoff current = repository.findById(next.id()).orElseThrow();
        return repository.transition(next.id(), current.status(), current.assignedAgentId(), next);
    }
}
