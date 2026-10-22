// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.ticketing.application.command.CheckInHandler;
import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.model.EntryMethod;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.model.ScanDirection;
import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.model.TicketStatus;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.CheckinLogRepository;
import com.nexaticket.ticketing.domain.port.TicketEventsPort;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Soát vé ở cửa với mọi cổng giả: thứ tự kiểm, những gì được ghi nhật ký, và những gì KHÔNG được lộ
 * ra cho nhân viên của tổ chức khác.
 *
 * <p>Câu UPDATE có điều kiện (thứ thực sự quyết định ai được vào) được giả bằng giá trị trả về của
 * {@link TicketRepository#enter}; hành vi của chính câu SQL đó thuộc về {@code TicketCheckinIT}.
 */
class CheckInHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:30:00Z");
    private static final Instant SHOW_STARTS = Instant.parse("2026-10-12T13:00:00Z");
    private static final Instant SHOW_ENDS = Instant.parse("2026-10-12T16:00:00Z");
    private static final UUID SESSION = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ORG = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID STAFF = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID EVENT = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final String QR = "header.payload.signature";
    private static final String GATE = "Cổng A";

    private TicketRepository tickets;
    private CheckinLogRepository log;
    private TicketSigner signer;
    private CatalogPort catalog;
    private TicketEventsPort events;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketRepository.class);
        log = mock(CheckinLogRepository.class);
        signer = mock(TicketSigner.class);
        catalog = mock(CatalogPort.class);
        events = mock(TicketEventsPort.class);
        when(catalog.sessionInfo(SESSION))
                .thenReturn(
                        Optional.of(new CatalogPort.SessionInfo(EVENT, "Đêm nhạc Mùa Thu", SHOW_STARTS, SHOW_ENDS)));
    }

    @Nested
    @DisplayName("Đường thành công")
    class DuongThanhCong {

        @Test
        @DisplayName("QR hợp lệ, vé đúng suất, cửa đang mở: cho vào, ghi nhật ký, báo khách lần đầu")
        void qr_hop_le_cho_vao() {
            Ticket ticket = outsideNeverEntered();
            givenQrFor(ticket);
            when(tickets.enter(ticket.id(), STAFF, NOW, GATE, true)).thenReturn(Optional.of(1));

            CheckInHandler.Result result = handler(true).handle(scanIn(QR));

            assertThat(result.result()).isEqualTo("ACCEPTED");
            assertThat(result.accepted()).isTrue();
            assertThat(result.direction()).isEqualTo("IN");
            assertThat(result.ticketId()).isEqualTo(ticket.id());
            assertThat(result.seatCode()).isEqualTo("A-1");
            assertThat(result.holderName()).isEqualTo("Nguyễn Văn An");
            assertThat(result.entryCount()).isEqualTo(1);
            assertThat(result.lastEntryAt()).isEqualTo(NOW);
            assertThat(result.lastEntryGate()).isEqualTo(GATE);
            assertThat(result.note()).isNull();
            verify(events).checkedIn(ticket, NOW, GATE, "Đêm nhạc Mùa Thu");

            CheckinLogRepository.Scan logged = loggedScan();
            assertThat(logged.result()).isEqualTo(CheckinResult.ACCEPTED);
            assertThat(logged.method()).isEqualTo(EntryMethod.QR);
            assertThat(logged.ticketId()).isEqualTo(ticket.id());
            assertThat(logged.organizationId()).isEqualTo(ORG);
            assertThat(logged.staffId()).isEqualTo(STAFF);
            assertThat(logged.deviceId()).isEqualTo("may-1");
        }

        @Test
        @DisplayName("Vào lại sau khi đã ra: cho vào, ghi chú 'Vào lại lần n', KHÔNG báo khách lần nữa")
        void vao_lai_khong_bao_khach() {
            Ticket ticket = exitedAfter(1);
            givenQrFor(ticket);
            when(tickets.enter(ticket.id(), STAFF, NOW, GATE, true)).thenReturn(Optional.of(2));

            CheckInHandler.Result result = handler(true).handle(scanIn(QR));

            assertThat(result.result()).isEqualTo("ACCEPTED");
            assertThat(result.note()).isEqualTo("Vào lại lần 1");
            assertThat(result.entryCount()).isEqualTo(2);
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("Quét Ra vé đang ở trong: EXITED, giữ lần vào gần nhất để nhân viên đối chiếu")
        void quet_ra() {
            Ticket ticket = inside();
            givenQrFor(ticket);
            when(tickets.exit(ticket.id(), NOW)).thenReturn(true);

            CheckInHandler.Result result = handler(true).handle(scan(QR, null, "OUT", false, null));

            assertThat(result.result()).isEqualTo("EXITED");
            assertThat(result.direction()).isEqualTo("OUT");
            assertThat(result.note()).isNull();
            assertThat(result.lastEntryAt()).isEqualTo(ticket.lastEntryAt());
            assertThat(loggedScan().result()).isEqualTo(CheckinResult.EXITED);
            verify(tickets, never()).enter(any(), any(), any(), any(), anyBoolean());
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("Quét Ra sau khi suất đã kết thúc vẫn được — không thì số 'đang ở trong' không về 0")
        void quet_ra_sau_khi_suat_ket_thuc() {
            Ticket ticket = inside();
            givenQrFor(ticket);
            when(tickets.exit(ticket.id(), NOW)).thenReturn(true);
            when(catalog.sessionInfo(SESSION))
                    .thenReturn(Optional.of(new CatalogPort.SessionInfo(
                            EVENT, "Đêm qua", NOW.minus(Duration.ofHours(8)), NOW.minus(Duration.ofHours(5)))));

            assertThat(handler(true).handle(scan(QR, null, "OUT", false, null)).result())
                    .isEqualTo("EXITED");
        }

        @Test
        @DisplayName("Mã vé gõ tay: tìm theo tiền tố trong tổ chức, không đụng tới chữ ký, ghi phương thức CODE")
        void ma_go_tay() {
            Ticket ticket = outsideNeverEntered();
            when(tickets.findByCodePrefix(ORG, "ABCDEF12")).thenReturn(List.of(ticket));
            when(tickets.enter(ticket.id(), STAFF, NOW, GATE, true)).thenReturn(Optional.of(1));

            CheckInHandler.Result result = handler(true).handle(scan(null, "  ABCDEF12 ", "IN", false, null));

            assertThat(result.result()).isEqualTo("ACCEPTED");
            assertThat(loggedScan().method()).isEqualTo(EntryMethod.CODE);
            verifyNoInteractions(signer);
        }

        @Test
        @DisplayName("Catalog im lặng: bỏ qua kiểm giờ, vẫn cho vào; sự kiện check-in không có tên sự kiện")
        void catalog_im_lang_van_cho_vao() {
            Ticket ticket = outsideNeverEntered();
            givenQrFor(ticket);
            when(catalog.sessionInfo(SESSION)).thenReturn(Optional.empty());
            when(tickets.enter(ticket.id(), STAFF, NOW, GATE, true)).thenReturn(Optional.of(1));

            assertThat(handler(true).handle(scanIn(QR)).result()).isEqualTo("ACCEPTED");
            verify(events).checkedIn(ticket, NOW, GATE, null);
        }
    }

    @Nested
    @DisplayName("Mã không hợp lệ")
    class MaKhongHopLe {

        @Test
        @DisplayName("Chữ ký sai hoặc token bị sửa: INVALID_TOKEN, không tra database, không lộ gì")
        void chu_ky_sai() {
            when(signer.verify("gia.mao.chuky")).thenReturn(Optional.empty());

            CheckInHandler.Result result = handler(true).handle(scanIn("gia.mao.chuky"));

            assertThat(result.result()).isEqualTo("INVALID_TOKEN");
            assertAnonymous(result);
            verifyNoInteractions(tickets);
            CheckinLogRepository.Scan logged = loggedScan();
            assertThat(logged.ticketId()).isNull();
            assertThat(logged.result()).isEqualTo(CheckinResult.INVALID_TOKEN);
        }

        @Test
        @DisplayName("Không gửi QR lẫn mã vé: INVALID_TOKEN, không gọi bộ kiểm chữ ký với chuỗi rỗng")
        void khong_gui_gi() {
            CheckInHandler.Result result = handler(true).handle(scan("  ", "", "IN", false, null));

            assertThat(result.result()).isEqualTo("INVALID_TOKEN");
            verifyNoInteractions(signer, tickets);
        }

        @Test
        @DisplayName("Chữ ký đúng nhưng không có vé mang mã đó: NOT_FOUND")
        void chu_ky_dung_khong_co_ve() {
            UUID jti = UUID.randomUUID();
            when(signer.verify(QR)).thenReturn(Optional.of(new QrToken(jti, NOW.getEpochSecond() + 3600)));
            when(tickets.findById(jti)).thenReturn(Optional.empty());

            CheckInHandler.Result result = handler(true).handle(scanIn(QR));

            assertThat(result.result()).isEqualTo("NOT_FOUND");
            assertAnonymous(result);
        }

        @Test
        @DisplayName("Mã gõ tay ngắn hơn 8 ký tự: từ chối mà không tra database")
        void ma_go_tay_qua_ngan() {
            CheckInHandler.Result result = handler(true).handle(scan(null, "ABC1", "IN", false, null));

            assertThat(result.result()).isEqualTo("NOT_FOUND");
            assertThat(result.note()).contains("8 ký tự");
            verifyNoInteractions(tickets);
        }

        @Test
        @DisplayName("Mã gõ tay chứa ký tự lạ (thử ký tự đại diện của LIKE): từ chối mà không tra database")
        void ma_go_tay_ky_tu_la() {
            CheckInHandler.Result result = handler(true).handle(scan(null, "ABCD%___", "IN", false, null));

            assertThat(result.result()).isEqualTo("NOT_FOUND");
            verifyNoInteractions(tickets);
        }

        @Test
        @DisplayName("Mã gõ tay trùng hai vé: yêu cầu gõ thêm, không đoán bừa vé nào")
        void ma_go_tay_trung() {
            when(tickets.findByCodePrefix(ORG, "ABCDEF12"))
                    .thenReturn(List.of(outsideNeverEntered(), outsideNeverEntered()));

            CheckInHandler.Result result = handler(true).handle(scan(null, "ABCDEF12", "IN", false, null));

            assertThat(result.result()).isEqualTo("NOT_FOUND");
            assertThat(result.note()).contains("trùng");
            verify(tickets, never()).enter(any(), any(), any(), any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("Phân quyền và sai vé")
    class PhanQuyen {

        @Test
        @DisplayName("Vé của tổ chức khác: từ chối, KHÔNG trả nhãn ghế hay tên khách, vẫn ghi nhật ký kèm id vé")
        void ve_cua_to_chuc_khac() {
            Ticket foreign = ticket(UUID.randomUUID(), SESSION, TicketStatus.VALID, null);
            givenQrFor(foreign);

            CheckInHandler.Result result = handler(true).handle(scanIn(QR));

            assertThat(result.result()).isEqualTo("WRONG_SESSION");
            assertAnonymous(result);
            assertThat(loggedScan().ticketId()).isEqualTo(foreign.id());
            verify(tickets, never()).enter(any(), any(), any(), any(), anyBoolean());
            verifyNoInteractions(catalog);
        }

        @Test
        @DisplayName("Vé đúng tổ chức nhưng của suất khác: WRONG_SESSION, chỉ ra suất của vé")
        void ve_suat_khac() {
            UUID otherSession = UUID.randomUUID();
            Ticket ticket = ticket(ORG, otherSession, TicketStatus.VALID, null);
            givenQrFor(ticket);

            CheckInHandler.Result result = handler(true).handle(scanIn(QR));

            assertThat(result.result()).isEqualTo("WRONG_SESSION");
            assertThat(result.ticketSessionId()).isEqualTo(otherSession);
            assertThat(result.note()).isEqualTo("Vé thuộc suất diễn khác");
            verify(tickets, never()).enter(any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Vé đã thu hồi: REVOKED ở cả hai hướng")
        void ve_thu_hoi() {
            Ticket revoked = ticket(ORG, SESSION, TicketStatus.REVOKED, null);
            givenQrFor(revoked);

            assertThat(handler(true).handle(scanIn(QR)).result()).isEqualTo("REVOKED");
            assertThat(handler(true).handle(scan(QR, null, "OUT", false, null)).result())
                    .isEqualTo("REVOKED");
            verify(tickets, never()).exit(any(), any());
        }
    }

    @Nested
    @DisplayName("Khung giờ cửa")
    class KhungGio {

        @Test
        @DisplayName("Chưa tới giờ mở cổng (sớm hơn 2 giờ trước giờ diễn một giây): NOT_OPEN_YET")
        void chua_mo_cong() {
            Ticket ticket = outsideNeverEntered();
            givenQrFor(ticket);

            CheckInHandler.Result result = handler(
                            true, SHOW_STARTS.minus(Duration.ofHours(2)).minusSeconds(1))
                    .handle(scanIn(QR));

            assertThat(result.result()).isEqualTo("NOT_OPEN_YET");
            assertThat(result.note()).isEqualTo("Chưa tới giờ mở cổng");
            verify(tickets, never()).enter(any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Đúng giây mở cổng: cho vào")
        void dung_gio_mo_cong() {
            Instant opens = SHOW_STARTS.minus(Duration.ofHours(2));
            Ticket ticket = outsideNeverEntered();
            givenQrFor(ticket, opens);
            when(tickets.enter(ticket.id(), STAFF, opens, GATE, true)).thenReturn(Optional.of(1));

            assertThat(handler(true, opens).handle(scanIn(QR)).result()).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("Suất đã kết thúc: SESSION_ENDED, vé không bị ghi là đã vào")
        void suat_da_ket_thuc() {
            Instant afterEnd = SHOW_ENDS.plusSeconds(1);
            Ticket ticket = outsideNeverEntered();
            givenQrFor(ticket, afterEnd);

            CheckInHandler.Result result = handler(true, afterEnd).handle(scanIn(QR));

            assertThat(result.result()).isEqualTo("SESSION_ENDED");
            verify(tickets, never()).enter(any(), any(), any(), any(), anyBoolean());
            assertThat(loggedScan().result()).isEqualTo(CheckinResult.SESSION_ENDED);
        }
    }

    @Nested
    @DisplayName("Vào, ra, vào lại")
    class VaoRaVaoLai {

        @Test
        @DisplayName("Vé đang ở trong quét Vào lần nữa: ALREADY_CHECKED_IN kèm lúc vào và cổng vào trước")
        void dang_o_trong() {
            Ticket ticket = inside();
            givenQrFor(ticket);

            CheckInHandler.Result result = handler(true).handle(scanIn(QR));

            assertThat(result.result()).isEqualTo("ALREADY_CHECKED_IN");
            assertThat(result.lastEntryAt()).isEqualTo(ticket.lastEntryAt());
            assertThat(result.lastEntryGate()).isEqualTo("Cổng B");
            assertThat(result.note()).isEqualTo("Vé đang ở trong khu vực");
            verify(tickets, never()).enter(any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Tắt vào lại: vé đã ra ngoài bị từ chối REENTRY_NOT_ALLOWED")
        void tat_vao_lai() {
            Ticket ticket = exitedAfter(1);
            givenQrFor(ticket);

            CheckInHandler.Result result = handler(false).handle(scanIn(QR));

            assertThat(result.result()).isEqualTo("REENTRY_NOT_ALLOWED");
            verify(tickets, never()).enter(any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Tắt vào lại không chặn lần vào ĐẦU TIÊN, và cấu hình được truyền xuống câu UPDATE")
        void tat_vao_lai_van_cho_lan_dau() {
            Ticket ticket = outsideNeverEntered();
            givenQrFor(ticket);
            when(tickets.enter(ticket.id(), STAFF, NOW, GATE, false)).thenReturn(Optional.of(1));

            assertThat(handler(false).handle(scanIn(QR)).result()).isEqualTo("ACCEPTED");
            verify(tickets).enter(ticket.id(), STAFF, NOW, GATE, false);
        }

        @Test
        @DisplayName("Quét Ra vé chưa vào: NOT_INSIDE, không ghi nhận ra")
        void ra_khi_chua_vao() {
            Ticket ticket = outsideNeverEntered();
            givenQrFor(ticket);

            CheckInHandler.Result result = handler(true).handle(scan(QR, null, "OUT", false, null));

            assertThat(result.result()).isEqualTo("NOT_INSIDE");
            verify(tickets, never()).exit(any(), any());
        }
    }

    @Nested
    @DisplayName("Hai máy quét cùng lúc")
    class HaiMayCungLuc {

        @Test
        @DisplayName("Thua cuộc đua lúc vào: ALREADY_CHECKED_IN 'ngay lúc này', không báo khách")
        void thua_cuoc_dua_vao() {
            Ticket ticket = outsideNeverEntered();
            givenQrFor(ticket);
            when(tickets.enter(ticket.id(), STAFF, NOW, GATE, true)).thenReturn(Optional.empty());

            CheckInHandler.Result result = handler(true).handle(scanIn(QR));

            assertThat(result.result()).isEqualTo("ALREADY_CHECKED_IN");
            assertThat(result.entryCount()).isEqualTo(1);
            assertThat(result.lastEntryAt()).isEqualTo(NOW);
            assertThat(loggedScan().result()).isEqualTo(CheckinResult.ALREADY_CHECKED_IN);
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("Thua cuộc đua lúc ra: NOT_INSIDE kèm lý do trong nhật ký")
        void thua_cuoc_dua_ra() {
            Ticket ticket = inside();
            givenQrFor(ticket);
            when(tickets.exit(ticket.id(), NOW)).thenReturn(false);

            CheckInHandler.Result result = handler(true).handle(scan(QR, null, "OUT", false, null));

            assertThat(result.result()).isEqualTo("NOT_INSIDE");
            CheckinLogRepository.Scan logged = loggedScan();
            assertThat(logged.result()).isEqualTo(CheckinResult.NOT_INSIDE);
            assertThat(logged.note()).isEqualTo("Vé chưa vào hoặc đã ra ngoài");
            assertThat(logged.direction()).isEqualTo(ScanDirection.OUT);
        }
    }

    @Nested
    @DisplayName("Cho vào thủ công")
    class ChoVaoThuCong {

        @Test
        @DisplayName("Vé đang ở trong, có lý do: ghi đè, lý do vào nhật ký, phương thức OVERRIDE")
        void ghi_de_co_ly_do() {
            Ticket ticket = inside();
            givenQrFor(ticket);
            when(tickets.forceEnter(ticket.id(), NOW, GATE)).thenReturn(Optional.of(3));

            CheckInHandler.Result result = handler(true).handle(scan(QR, null, "IN", true, "  Khách ra quên quét  "));

            assertThat(result.result()).isEqualTo("ACCEPTED");
            assertThat(result.note()).isEqualTo("Cho vào thủ công");
            assertThat(result.entryCount()).isEqualTo(3);
            CheckinLogRepository.Scan logged = loggedScan();
            assertThat(logged.method()).isEqualTo(EntryMethod.OVERRIDE);
            assertThat(logged.note()).isEqualTo("Khách ra quên quét");
            verify(tickets, never()).enter(any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Ghi đè mà không có lý do: từ chối, không đổi trạng thái vé")
        void ghi_de_khong_ly_do() {
            Ticket ticket = inside();
            givenQrFor(ticket);

            CheckInHandler.Result result = handler(true).handle(scan(QR, null, "IN", true, "   "));

            assertThat(result.result()).isEqualTo("ALREADY_CHECKED_IN");
            assertThat(result.note()).contains("lý do");
            verify(tickets, never()).forceEnter(any(), any(), anyString());
            verify(tickets, never()).enter(any(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Vé vừa được quét Ra trước lúc ghi đè: rơi về lượt Vào thường")
        void ghi_de_nhung_ve_vua_ra() {
            Ticket ticket = inside();
            givenQrFor(ticket);
            when(tickets.forceEnter(ticket.id(), NOW, GATE)).thenReturn(Optional.empty());
            when(tickets.enter(eq(ticket.id()), eq(STAFF), eq(NOW), eq(GATE), anyBoolean()))
                    .thenReturn(Optional.of(2));

            CheckInHandler.Result result = handler(true).handle(scan(QR, null, "IN", true, "Khách ra quên quét"));

            assertThat(result.result()).isEqualTo("ACCEPTED");
            assertThat(result.entryCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Ghi đè không cứu được vé thu hồi")
        void ghi_de_khong_cuu_ve_thu_hoi() {
            Ticket revoked = ticket(ORG, SESSION, TicketStatus.REVOKED, null);
            givenQrFor(revoked);

            CheckInHandler.Result result = handler(true).handle(scan(QR, null, "IN", true, "Ban tổ chức cho vào"));

            assertThat(result.result()).isEqualTo("REVOKED");
            verify(tickets, never()).forceEnter(any(), any(), any());
        }
    }

    // --- dựng dữ liệu -----------------------------------------------------------------------

    private CheckInHandler handler(boolean reentryAllowed) {
        return handler(reentryAllowed, NOW);
    }

    private CheckInHandler handler(boolean reentryAllowed, Instant now) {
        return new CheckInHandler(
                tickets,
                log,
                signer,
                catalog,
                events,
                Clock.fixed(now, ZoneOffset.UTC),
                reentryAllowed,
                Duration.ofHours(2),
                Duration.ofHours(6));
    }

    private void givenQrFor(Ticket ticket) {
        givenQrFor(ticket, NOW);
    }

    private void givenQrFor(Ticket ticket, Instant now) {
        when(signer.verify(QR)).thenReturn(Optional.of(new QrToken(ticket.id(), now.getEpochSecond() + 3600)));
        when(tickets.findById(ticket.id())).thenReturn(Optional.of(ticket));
    }

    private static CheckInHandler.Command scanIn(String qr) {
        return scan(qr, null, "IN", false, null);
    }

    private static CheckInHandler.Command scan(
            String qr, String code, String direction, boolean override, String reason) {
        return new CheckInHandler.Command(qr, code, SESSION, STAFF, ORG, "may-1", direction, GATE, override, reason);
    }

    private CheckinLogRepository.Scan loggedScan() {
        ArgumentCaptor<CheckinLogRepository.Scan> captor = ArgumentCaptor.forClass(CheckinLogRepository.Scan.class);
        verify(log).record(captor.capture());
        return captor.getValue();
    }

    private static void assertAnonymous(CheckInHandler.Result result) {
        assertThat(result.ticketId()).isNull();
        assertThat(result.seatCode()).isNull();
        assertThat(result.seatLabel()).isNull();
        assertThat(result.holderName()).isNull();
        assertThat(result.zoneCode()).isNull();
        assertThat(result.ticketSessionId()).isNull();
    }

    private static Ticket outsideNeverEntered() {
        return ticket(ORG, SESSION, TicketStatus.VALID, null);
    }

    private static Ticket inside() {
        Instant entered = NOW.minus(Duration.ofMinutes(20));
        return ticket(ORG, SESSION, TicketStatus.CHECKED_IN, entered).withPresence(true, 1, entered, "Cổng B", null);
    }

    private static Ticket exitedAfter(int entries) {
        Instant entered = NOW.minus(Duration.ofMinutes(40));
        return ticket(ORG, SESSION, TicketStatus.CHECKED_IN, entered)
                .withPresence(false, entries, entered, "Cổng B", NOW.minus(Duration.ofMinutes(10)));
    }

    private static Ticket ticket(UUID organizationId, UUID sessionId, TicketStatus status, Instant checkedInAt) {
        return Ticket.rehydrate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                sessionId,
                organizationId,
                UUID.randomUUID(),
                null,
                "A-1",
                "A",
                "SEATED",
                "Hàng A ghế 1",
                "VIP",
                "Nguyễn Văn An",
                status,
                checkedInAt,
                null);
    }
}
