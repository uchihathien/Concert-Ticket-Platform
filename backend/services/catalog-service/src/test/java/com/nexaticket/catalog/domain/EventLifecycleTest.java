// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.EventStatus;
import com.nexaticket.catalog.domain.model.PurchaseLimits;
import com.nexaticket.catalog.domain.model.Slug;
import com.nexaticket.catalog.domain.model.TicketType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Vòng đời sự kiện và các giá trị con của nó, kiểm ở mức domain.
 *
 * <p>Bổ sung cho {@code EventPreflightTest} (luật "sẵn sàng để bán"): ở đây là các phép chuyển
 * trạng thái nháp → đang bán → rút xuống → huỷ, luật xoá, ngữ nghĩa patch của {@code rename}, và
 * những bất biến nhỏ của suất diễn, hạng vé, trần mua vé, slug. Mọi mốc thời gian là hằng số.
 */
class EventLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Nested
    @DisplayName("Chuyển trạng thái")
    class ChuyenTrangThai {

        @Test
        @DisplayName("bản nháp mới tạo ở DRAFT, chưa có mốc publish, và xoá được")
        void ban_nhap_moi() {
            Event event = draft();

            assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
            assertThat(event.publishedAt()).isNull();
            assertThat(event.isDeletable()).isTrue();
        }

        @Test
        @DisplayName("publish ghi đúng mốc thời gian được truyền vào, không tự đọc đồng hồ")
        void publish_ghi_moc() {
            Event event = draft();

            event.publish(NOW);

            assertThat(event.status()).isEqualTo(EventStatus.PUBLISHED);
            assertThat(event.publishedAt()).isEqualTo(NOW);
            assertThat(event.isDeletable()).isFalse();
        }

        @Test
        @DisplayName("đang bán thì không publish lần nữa")
        void khong_publish_hai_lan() {
            Event event = draft();
            event.publish(NOW);

            assertThatThrownBy(() -> event.publish(NOW.plusSeconds(60))).isInstanceOf(IllegalStateException.class);
            assertThat(event.publishedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("rút xuống xoá mốc publish — ràng buộc ck_published_at đòi đúng như vậy")
        void rut_xuong_xoa_moc() {
            Event event = draft();
            event.publish(NOW);

            event.unpublish();

            assertThat(event.status()).isEqualTo(EventStatus.UNPUBLISHED);
            assertThat(event.publishedAt()).isNull();
        }

        @Test
        @DisplayName("rút xuống rồi publish lại được, với mốc mới")
        void publish_lai_sau_khi_rut() {
            Event event = draft();
            event.publish(NOW);
            event.unpublish();

            event.publish(NOW.plus(Duration.ofDays(1)));

            assertThat(event.status()).isEqualTo(EventStatus.PUBLISHED);
            assertThat(event.publishedAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = EventStatus.class,
                names = {"DRAFT", "UNPUBLISHED", "CANCELLED"})
        @DisplayName("chỉ rút xuống được sự kiện đang bán")
        void chi_rut_duoc_khi_dang_ban(EventStatus status) {
            Event event = withStatus(status);

            assertThatThrownBy(event::unpublish).isInstanceOf(IllegalStateException.class);
            assertThat(event.status()).isEqualTo(status);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = EventStatus.class,
                names = {"DRAFT", "PUBLISHED", "UNPUBLISHED"})
        @DisplayName("huỷ được từ mọi trạng thái chưa huỷ, và xoá mốc publish")
        void huy_tu_moi_trang_thai(EventStatus status) {
            Event event = withStatus(status);

            event.cancel();

            assertThat(event.status()).isEqualTo(EventStatus.CANCELLED);
            assertThat(event.publishedAt()).isNull();
        }

        @Test
        @DisplayName("huỷ là trạng thái cuối: không huỷ lại, không publish lại")
        void huy_la_trang_thai_cuoi() {
            Event event = withStatus(EventStatus.CANCELLED);

            assertThatThrownBy(event::cancel).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> event.publish(NOW)).isInstanceOf(IllegalStateException.class);
            assertThat(event.isDeletable()).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = EventStatus.class,
                names = {"PUBLISHED", "UNPUBLISHED", "CANCELLED"})
        @DisplayName("chỉ bản nháp mới xoá hẳn được — dữ liệu ở service khác sẽ mồ côi")
        void chi_ban_nhap_moi_xoa_duoc(EventStatus status) {
            assertThat(withStatus(status).isDeletable()).isFalse();
        }

        @Test
        @DisplayName("khách chỉ thấy PUBLISHED; UNPUBLISHED không phải bản nháp nhưng cũng bị ẩn")
        void chi_published_hien_cong_khai() {
            assertThat(EventStatus.PUBLISHED.isVisibleToPublic()).isTrue();
            assertThat(EventStatus.DRAFT.isVisibleToPublic()).isFalse();
            assertThat(EventStatus.UNPUBLISHED.isVisibleToPublic()).isFalse();
            assertThat(EventStatus.CANCELLED.isVisibleToPublic()).isFalse();
        }
    }

    @Nested
    @DisplayName("Tạo và sửa nội dung")
    class NoiDung {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("tiêu đề rỗng hoặc toàn khoảng trắng bị từ chối")
        void tieu_de_rong(String title) {
            assertThatThrownBy(() -> Event.draft(
                            UUID.randomUUID(), UUID.randomUUID(), new Slug("abc"), title, null, null, "nhac", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rename: null giữ nguyên, chuỗi rỗng xoá tóm tắt/mô tả/ảnh, nhưng KHÔNG xoá tiêu đề")
        void rename_la_phep_patch() {
            Event event = Event.draft(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new Slug("dem-nhac"),
                    "Đêm nhạc",
                    "Tóm tắt",
                    "Mô tả",
                    "nhac-song",
                    "https://cdn.example/a.jpg");

            event.rename("", "", "", "  ", "");

            assertThat(event.title()).isEqualTo("Đêm nhạc");
            assertThat(event.category()).isEqualTo("nhac-song");
            assertThat(event.summary()).isEmpty();
            assertThat(event.description()).isEmpty();
            assertThat(event.posterUrl()).isEmpty();

            event.rename(null, null, null, null, null);

            assertThat(event.title()).isEqualTo("Đêm nhạc");
            assertThat(event.posterUrl()).isEmpty();
        }

        @Test
        @DisplayName("nội dung do người dùng gõ được giữ nguyên văn — API JSON không tự escape HTML")
        void giu_nguyen_noi_dung() {
            String raw = "<script>alert('x')</script> & \"Hòa âm\"";
            Event event = draft();

            event.rename(raw, raw, raw, null, null);

            assertThat(event.title()).isEqualTo(raw);
            assertThat(event.description()).isEqualTo(raw);
        }

        @Test
        @DisplayName("danh sách suất trả ra là bản sao — sửa nó không đổi được aggregate")
        void danh_sach_suat_la_ban_sao() {
            Event event = draft();
            List<EventSession> sessions = event.sessions();

            assertThatThrownBy(() -> sessions.add(session(event.id(), NOW, NOW.plusSeconds(1), NOW.plusSeconds(2))))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(event.sessions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Suất diễn và cửa bán")
    class SuatDien {

        @Test
        @DisplayName("thiếu giờ diễn hoặc cửa bán thì không dựng được suất")
        void thieu_moc_bat_buoc() {
            UUID eventId = UUID.randomUUID();

            assertThatThrownBy(() -> session(eventId, null, NOW, NOW.plusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> session(eventId, NOW, null, NOW.plusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> session(eventId, NOW, NOW, null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("cửa bán đóng đúng lúc mở là cửa bán rỗng — không hợp lệ")
        void cua_ban_rong() {
            Instant startsAt = NOW.plus(Duration.ofDays(10));

            assertThat(session(UUID.randomUUID(), startsAt, NOW, NOW).hasValidSalesWindow())
                    .isFalse();
        }

        @Test
        @DisplayName("cửa bán đóng trước khi mở — không hợp lệ")
        void cua_ban_nguoc() {
            Instant startsAt = NOW.plus(Duration.ofDays(10));

            assertThat(session(UUID.randomUUID(), startsAt, NOW.plusSeconds(60), NOW)
                            .hasValidSalesWindow())
                    .isFalse();
        }

        @Test
        @DisplayName("cửa bán mở đúng giờ diễn — không còn gì để bán trước giờ diễn, không hợp lệ")
        void mo_ban_dung_gio_dien() {
            Instant startsAt = NOW.plus(Duration.ofDays(10));

            assertThat(session(UUID.randomUUID(), startsAt, startsAt, startsAt.plusSeconds(3600))
                            .hasValidSalesWindow())
                    .isFalse();
        }

        @Test
        @DisplayName("giờ Việt Nam và UTC là cùng một thời điểm: 19:30 ở Hà Nội = 12:30Z")
        void mui_gio_viet_nam() {
            // Frontend gửi Instant (UTC). Nếu backend lỡ đọc giờ địa phương như UTC thì cửa bán
            // lệch 7 tiếng — đủ để một suất 19:30 tối mở bán SAU giờ diễn.
            Instant startsAt =
                    LocalDateTime.parse("2026-12-24T19:30").atZone(VIETNAM).toInstant();
            Instant openAt =
                    LocalDateTime.parse("2026-12-24T12:30").atZone(VIETNAM).toInstant();

            assertThat(startsAt).isEqualTo(Instant.parse("2026-12-24T12:30:00Z"));
            assertThat(session(UUID.randomUUID(), startsAt, openAt, startsAt).hasValidSalesWindow())
                    .isTrue();
        }

        @Test
        @DisplayName("domain chấp nhận giờ kết thúc trước giờ bắt đầu — chỉ ghi nhận, không có luật nào chặn")
        void gio_ket_thuc_truoc_gio_bat_dau() {
            // Ghi lại hành vi hiện có: không lớp nào (domain, preflight, ràng buộc database) kiểm
            // endsAt > startsAt. Xem báo cáo — đây là khoảng trống chứ không phải luật.
            Instant startsAt = NOW.plus(Duration.ofDays(10));
            EventSession weird = new EventSession(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    startsAt,
                    startsAt.minus(Duration.ofHours(2)),
                    NOW,
                    startsAt,
                    null,
                    null,
                    null,
                    null,
                    List.of());

            assertThat(weird.hasValidSalesWindow()).isTrue();
        }

        @Test
        @DisplayName("giá \"từ\" là giá thấp nhất; không có hạng vé thì null chứ không phải 0")
        void gia_tu() {
            UUID sessionId = UUID.randomUUID();
            Instant startsAt = NOW.plus(Duration.ofDays(10));
            EventSession empty = session(UUID.randomUUID(), startsAt, NOW, startsAt);
            EventSession priced = new EventSession(
                    sessionId,
                    UUID.randomUUID(),
                    startsAt,
                    null,
                    NOW,
                    startsAt,
                    null,
                    null,
                    null,
                    null,
                    List.of(
                            TicketType.create(sessionId, UUID.randomUUID(), "VIP", 2_000_000, 0),
                            TicketType.create(sessionId, UUID.randomUUID(), "Mời", 0, 1),
                            TicketType.create(sessionId, UUID.randomUUID(), "Thường", 500_000, 2)));

            assertThat(empty.fromPriceVnd()).isNull();
            assertThat(priced.fromPriceVnd()).isZero();
            assertThat(priced.sellsSomething()).isTrue();
            assertThat(empty.sellsSomething()).isFalse();
        }
    }

    @Nested
    @DisplayName("Hạng vé")
    class HangVe {

        @Test
        @DisplayName("giá âm bị từ chối")
        void gia_am() {
            assertThatThrownBy(() -> TicketType.create(UUID.randomUUID(), UUID.randomUUID(), "A", -1, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("giá 0 hợp lệ — vé mời")
        void gia_khong() {
            assertThat(TicketType.create(UUID.randomUUID(), UUID.randomUUID(), "Mời", 0, 0)
                            .priceVnd())
                    .isZero();
        }

        @Test
        @DisplayName("giá rất lớn vẫn giữ nguyên, không tràn số (long)")
        void gia_rat_lon() {
            assertThat(TicketType.create(UUID.randomUUID(), UUID.randomUUID(), "Siêu VIP", 9_000_000_000L, 0)
                            .priceVnd())
                    .isEqualTo(9_000_000_000L);
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", " "})
        @DisplayName("tên rỗng bị từ chối")
        void ten_rong(String name) {
            assertThatThrownBy(() -> TicketType.create(UUID.randomUUID(), UUID.randomUUID(), name, 1, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Trần mua vé")
    class TranMuaVe {

        private final PurchaseLimits cap = new PurchaseLimits(8, 10, 10, 20);

        @Test
        @DisplayName("null lấy trần nền tảng, số nhỏ hơn giữ nguyên, số lớn hơn bị kẹp xuống")
        void ke_thua_va_kep() {
            PurchaseLimits resolved = PurchaseLimits.resolve(null, 4, 999, 20, cap);

            assertThat(resolved).isEqualTo(new PurchaseLimits(8, 4, 10, 20));
        }

        @Test
        @DisplayName("trần 0 hoặc âm không dựng được")
        void tran_khong_duong() {
            assertThatThrownBy(() -> new PurchaseLimits(0, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PurchaseLimits.resolve(-3, null, null, null, cap))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Slug")
    class SlugTest {

        @Test
        @DisplayName("sinh từ tiếng Việt có dấu, kể cả chữ Đ")
        void sinh_tu_tieng_viet() {
            assertThat(Slug.from("Đêm nhạc Mùa Thu — 2026!").value()).isEqualTo("dem-nhac-mua-thu-2026");
        }

        @Test
        @DisplayName("tiêu đề không còn ký tự nào dùng được thì từ chối")
        void khong_sinh_duoc() {
            assertThatThrownBy(() -> Slug.from("!!! ???")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("tiêu đề rất dài bị cắt còn tối đa 96 ký tự và không kết thúc bằng gạch nối")
        void cat_tieu_de_dai() {
            Slug slug = Slug.from("a".repeat(95) + " b" + " c".repeat(50));

            assertThat(slug.value()).hasSizeLessThanOrEqualTo(96).doesNotEndWith("-");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"Hoa-Am", "hoa am", "hoa--am", "-hoa", "hoa-", "../etc", "hoa%20am", ""})
        @DisplayName("slug tự gõ sai định dạng bị từ chối")
        void slug_sai_dinh_dang(String value) {
            assertThatThrownBy(() -> new Slug(value)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("slug dài đúng 96 ký tự hợp lệ, 97 thì không")
        void bien_do_dai() {
            assertThatCode(() -> new Slug("a".repeat(96))).doesNotThrowAnyException();
            assertThatThrownBy(() -> new Slug("a".repeat(97))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private static Event draft() {
        return Event.draft(
                UUID.randomUUID(), UUID.randomUUID(), new Slug("su-kien"), "Sự kiện", null, null, "nhac-song", null);
    }

    private static Event withStatus(EventStatus status) {
        return new Event(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new Slug("su-kien"),
                "Sự kiện",
                null,
                null,
                "nhac-song",
                null,
                status,
                status == EventStatus.PUBLISHED ? NOW : null,
                List.of());
    }

    private static EventSession session(UUID eventId, Instant startsAt, Instant openAt, Instant closeAt) {
        return new EventSession(
                UUID.randomUUID(), eventId, startsAt, null, openAt, closeAt, null, null, null, null, List.of());
    }
}
