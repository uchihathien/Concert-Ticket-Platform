// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.model.GateWindow;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.model.ScanDirection;
import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.model.TicketStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Những khe còn trống của luật vé sau {@link TicketTest}: trạng thái lúc phát, thứ tự kiểm khi quét
 * Ra, hạn token QR và ý nghĩa của từng kết quả quét.
 */
class TicketLifecycleTest {

    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ORG = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final Instant ENTERED = Instant.parse("2026-10-12T12:00:00Z");

    @Nested
    @DisplayName("Phát vé")
    class PhatVe {

        @Test
        @DisplayName("Vé mới phát: VALID, đang ở ngoài, chưa vào lần nào, giữ nguyên nhãn snapshot")
        void ve_moi_phat_o_ngoai_va_giu_nhan() {
            UUID orderId = UUID.randomUUID();
            UUID itemId = UUID.randomUUID();

            Ticket ticket = Ticket.issue(
                    orderId,
                    itemId,
                    SESSION,
                    ORG,
                    UUID.randomUUID(),
                    null,
                    "A-1",
                    "A",
                    "SEATED",
                    "Hàng A ghế 1",
                    "VIP",
                    "Nguyễn Văn An");

            assertThat(ticket.status()).isEqualTo(TicketStatus.VALID);
            assertThat(ticket.inside()).isFalse();
            assertThat(ticket.entryCount()).isZero();
            assertThat(ticket.checkedInAt()).isNull();
            assertThat(ticket.lastEntryAt()).isNull();
            assertThat(ticket.orderId()).isEqualTo(orderId);
            assertThat(ticket.orderItemId()).isEqualTo(itemId);
            assertThat(ticket.seatLabel()).isEqualTo("Hàng A ghế 1");
            assertThat(ticket.ticketTypeName()).isEqualTo("VIP");
            assertThat(ticket.holderName()).isEqualTo("Nguyễn Văn An");
        }

        @Test
        @DisplayName("Mỗi vé có id ngẫu nhiên riêng — id đoán được là dò được vé của người khác")
        void moi_ve_mot_id_rieng() {
            Ticket first = Ticket.issue(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    SESSION,
                    ORG,
                    UUID.randomUUID(),
                    null,
                    "A-1",
                    "A",
                    "SEATED",
                    "1",
                    "VIP",
                    null);
            Ticket second = Ticket.issue(
                    first.orderId(),
                    UUID.randomUUID(),
                    SESSION,
                    ORG,
                    first.userId(),
                    null,
                    "A-2",
                    "A",
                    "SEATED",
                    "2",
                    "VIP",
                    null);

            assertThat(first.id()).isNotEqualTo(second.id());
        }

        @Test
        @DisplayName("Vé CHECKED_IN có từ trước khi có vào/ra được coi là đang ở trong, đã vào một lần")
        void ve_cu_da_soat_mac_dinh_o_trong() {
            Ticket legacy = rehydrate(TicketStatus.CHECKED_IN, ENTERED);

            assertThat(legacy.inside()).isTrue();
            assertThat(legacy.entryCount()).isEqualTo(1);
            assertThat(legacy.lastEntryAt()).isEqualTo(ENTERED);
        }

        @Test
        @DisplayName("withPresence ghi đè mặc định bằng trạng thái đọc từ database")
        void with_presence_ghi_de_mac_dinh() {
            Instant exitAt = ENTERED.plusSeconds(600);

            Ticket ticket =
                    rehydrate(TicketStatus.CHECKED_IN, ENTERED).withPresence(false, 3, ENTERED, "Cổng B", exitAt);

            assertThat(ticket.inside()).isFalse();
            assertThat(ticket.entryCount()).isEqualTo(3);
            assertThat(ticket.lastEntryGate()).isEqualTo("Cổng B");
            assertThat(ticket.lastExitAt()).isEqualTo(exitAt);
        }
    }

    @Nested
    @DisplayName("Thứ tự kiểm khi quét")
    class ThuTuKiem {

        @Test
        @DisplayName("Quét Ra ở sai suất: báo sai suất, không báo 'chưa vào'")
        void quet_ra_sai_suat() {
            Ticket inside = rehydrate(TicketStatus.CHECKED_IN, ENTERED);

            assertThat(inside.evaluate(UUID.randomUUID(), ScanDirection.OUT, true))
                    .isEqualTo(CheckinResult.WRONG_SESSION);
        }

        @Test
        @DisplayName("Vé thu hồi ở sai suất: sai suất được kiểm trước thu hồi")
        void ve_thu_hoi_sai_suat() {
            assertThat(rehydrate(TicketStatus.REVOKED, null).evaluate(UUID.randomUUID(), ScanDirection.IN, true))
                    .isEqualTo(CheckinResult.WRONG_SESSION);
        }

        @Test
        @DisplayName("Vé thu hồi vẫn bị chặn dù database ghi là đang ở ngoài và chưa vào lần nào")
        void ve_thu_hoi_khong_vao_duoc() {
            Ticket revoked = rehydrate(TicketStatus.REVOKED, null).withPresence(false, 0, null, null, null);

            assertThat(revoked.evaluate(SESSION, ScanDirection.IN, true)).isEqualTo(CheckinResult.REVOKED);
        }

        @Test
        @DisplayName("Vé đang ở trong: ALREADY_CHECKED_IN dù có tắt vào lại — dùng chung QR khác vào lại")
        void dang_o_trong_bat_ke_cau_hinh_vao_lai() {
            Ticket inside = rehydrate(TicketStatus.CHECKED_IN, ENTERED);

            assertThat(inside.evaluate(SESSION, ScanDirection.IN, false)).isEqualTo(CheckinResult.ALREADY_CHECKED_IN);
            assertThat(inside.evaluate(SESSION, ScanDirection.IN, true)).isEqualTo(CheckinResult.ALREADY_CHECKED_IN);
        }

        @Test
        @DisplayName("Vé đã ra ngoài rồi quét Ra lần nữa: NOT_INSIDE")
        void ra_hai_lan() {
            Ticket outside = rehydrate(TicketStatus.CHECKED_IN, ENTERED)
                    .withPresence(false, 2, ENTERED, "Cổng A", ENTERED.plusSeconds(60));

            assertThat(outside.evaluate(SESSION, ScanDirection.OUT, true)).isEqualTo(CheckinResult.NOT_INSIDE);
        }

        @Test
        @DisplayName("Đã vào nhiều lần, đang ở ngoài, tắt vào lại: REENTRY_NOT_ALLOWED")
        void nhieu_lan_vao_tat_vao_lai() {
            Ticket outside = rehydrate(TicketStatus.CHECKED_IN, ENTERED)
                    .withPresence(false, 5, ENTERED, "Cổng A", ENTERED.plusSeconds(60));

            assertThat(outside.evaluate(SESSION, ScanDirection.IN, false)).isEqualTo(CheckinResult.REENTRY_NOT_ALLOWED);
        }
    }

    @Nested
    @DisplayName("Token QR")
    class TokenQr {

        @Test
        @DisplayName("Hết hạn ĐÚNG tại giây exp, không phải sau đó")
        void het_han_dung_tai_exp() {
            QrToken token = new QrToken(UUID.randomUUID(), 1_800_000_000L);

            assertThat(token.isExpiredAt(1_799_999_999L)).isFalse();
            assertThat(token.isExpiredAt(1_800_000_000L)).isTrue();
            assertThat(token.isExpiredAt(1_800_000_001L)).isTrue();
        }
    }

    @Nested
    @DisplayName("Khung giờ cửa")
    class KhungGio {

        @Test
        @DisplayName("of() tính giờ mở = giờ diễn − khoảng mở trước; giờ đóng = giờ kết thúc khai báo")
        void tinh_gio_mo_dong() {
            Instant start = Instant.parse("2026-10-12T13:00:00Z");
            Instant end = Instant.parse("2026-10-12T16:00:00Z");

            GateWindow window = GateWindow.of(start, end, Duration.ofMinutes(90), Duration.ofHours(6));

            assertThat(window.opensAt()).isEqualTo(Instant.parse("2026-10-12T11:30:00Z"));
            assertThat(window.closesAt()).isEqualTo(end);
        }

        @Test
        @DisplayName("Không khai giờ kết thúc: đóng sau khoảng mặc định kể từ giờ diễn")
        void khong_khai_gio_ket_thuc() {
            Instant start = Instant.parse("2026-10-12T13:00:00Z");

            GateWindow window = GateWindow.of(start, null, Duration.ofHours(2), Duration.ofHours(6));

            assertThat(window.closesAt()).isEqualTo(Instant.parse("2026-10-12T19:00:00Z"));
            assertThat(window.check(Instant.parse("2026-10-12T19:00:00Z"))).isNull();
        }
    }

    @Nested
    @DisplayName("Ý nghĩa kết quả quét")
    class YNghiaKetQua {

        @Test
        @DisplayName("Chỉ ACCEPTED là 'cho vào'; ACCEPTED và EXITED là đã đổi trạng thái vé")
        void accepted_va_succeeded() {
            assertThat(EnumSet.allOf(CheckinResult.class).stream().filter(CheckinResult::accepted))
                    .containsExactly(CheckinResult.ACCEPTED);
            assertThat(EnumSet.allOf(CheckinResult.class).stream().filter(CheckinResult::succeeded))
                    .containsExactlyInAnyOrder(CheckinResult.ACCEPTED, CheckinResult.EXITED);
        }

        @ParameterizedTest
        @EnumSource(
                value = CheckinResult.class,
                names = {"ACCEPTED", "EXITED"},
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Mọi kết quả còn lại là từ chối")
        void con_lai_la_tu_choi(CheckinResult result) {
            assertThat(result.succeeded()).isFalse();
            assertThat(result.accepted()).isFalse();
        }
    }

    private static Ticket rehydrate(TicketStatus status, Instant checkedInAt) {
        return Ticket.rehydrate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                SESSION,
                ORG,
                UUID.randomUUID(),
                null,
                "A-1",
                "A",
                "SEATED",
                "1",
                "VIP",
                "Nguyễn Văn An",
                status,
                checkedInAt,
                null);
    }
}
