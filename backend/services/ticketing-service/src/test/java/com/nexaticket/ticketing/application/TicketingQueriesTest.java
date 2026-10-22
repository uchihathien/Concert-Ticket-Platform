// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import com.nexaticket.ticketing.application.query.BuyerExportQuery;
import com.nexaticket.ticketing.application.query.CheckinHistoryQuery;
import com.nexaticket.ticketing.application.query.GateStats;
import com.nexaticket.ticketing.application.query.GateStatsQuery;
import com.nexaticket.ticketing.application.query.TicketQueries;
import com.nexaticket.ticketing.application.query.TicketView;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.model.TicketStatus;
import com.nexaticket.ticketing.domain.port.CheckinLogRepository;
import com.nexaticket.ticketing.domain.port.ExportAuditPort;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Các đường đọc của ticketing: ví vé, lịch sử quét, thống kê và xuất danh sách người mua. */
class TicketingQueriesTest {

    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID ORG = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("40000000-0000-4000-8000-000000000002");
    private static final UUID ACTOR = UUID.fromString("40000000-0000-4000-8000-000000000003");

    @Nested
    @DisplayName("Ví vé của khách")
    class ViVe {

        private final TicketRepository tickets = mock(TicketRepository.class);
        private final TicketSigner signer = mock(TicketSigner.class);
        private final TicketQueries queries = new TicketQueries(tickets, signer, CLOCK);

        @Test
        @DisplayName("Vé của đơn chỉ trả những vé thuộc người đang hỏi — đoán được id đơn không thấy được vé")
        void chi_thay_ve_cua_minh() {
            UUID me = UUID.randomUUID();
            UUID order = UUID.randomUUID();
            Ticket mine = ticket(order, me);
            Ticket someoneElse = ticket(order, UUID.randomUUID());
            when(tickets.findByOrder(order)).thenReturn(List.of(mine, someoneElse));
            when(signer.sign(any())).thenReturn("jws");

            List<TicketView> views = queries.forOrder(order, me);

            assertThat(views).extracting(TicketView::id).containsExactly(mine.id());
            verify(signer)
                    .sign(new QrToken(mine.id(), NOW.plus(Duration.ofHours(12)).getEpochSecond()));
        }

        @Test
        @DisplayName("Mỗi lần đọc ký lại token, hạn 12 giờ kể từ lúc đọc")
        void ky_lai_moi_lan_doc() {
            UUID me = UUID.randomUUID();
            Ticket mine = ticket(UUID.randomUUID(), me);
            when(tickets.findByUser(me, 50, 0)).thenReturn(List.of(mine));
            when(signer.sign(new QrToken(mine.id(), NOW.getEpochSecond() + 12 * 3600)))
                    .thenReturn("a.b.c");

            List<TicketView> views = queries.forUser(me, 50, 0);

            assertThat(views).singleElement().satisfies(view -> {
                assertThat(view.qrToken()).isEqualTo("a.b.c");
                assertThat(view.status()).isEqualTo("VALID");
                assertThat(view.seatCode()).isEqualTo("B-7");
            });
        }

        @Test
        @DisplayName("Đơn không có vé nào của mình: danh sách rỗng, không ký gì")
        void don_cua_nguoi_khac() {
            UUID order = UUID.randomUUID();
            when(tickets.findByOrder(order)).thenReturn(List.of(ticket(order, UUID.randomUUID())));

            assertThat(queries.forOrder(order, UUID.randomUUID())).isEmpty();
            verifyNoInteractions(signer);
        }
    }

    @Nested
    @DisplayName("Lịch sử quét của một suất")
    class LichSuQuet {

        private final CheckinLogRepository log = mock(CheckinLogRepository.class);
        private final TicketRepository tickets = mock(TicketRepository.class);
        private final CheckinHistoryQuery query = new CheckinHistoryQuery(log, tickets);

        @Test
        @DisplayName("Kích thước trang bị kẹp trong [1, 100], trang âm thành 0, offset = trang × kích thước")
        void kep_phan_trang() {
            when(tickets.presenceOf(ORG, SESSION)).thenReturn(new TicketRepository.Presence(0, 0));

            var huge = query.forSession(ORG, SESSION, null, 3, 10_000);
            var zero = query.forSession(ORG, SESSION, null, -5, 0);

            assertThat(huge.size()).isEqualTo(100);
            assertThat(huge.page()).isEqualTo(3);
            verify(log).history(ORG, SESSION, null, 100, 300);
            assertThat(zero.size()).isEqualTo(1);
            assertThat(zero.page()).isZero();
            verify(log).history(ORG, SESSION, null, 1, 0);
        }

        @Test
        @DisplayName("Mã vé là 8 ký tự đầu viết hoa; lần quét không ra vé thì không có mã; kèm số đang ở trong")
        void ma_ve_va_so_dang_o_trong() {
            UUID ticketId = UUID.fromString("abcdef12-0000-4000-8000-000000000009");
            when(tickets.presenceOf(ORG, SESSION)).thenReturn(new TicketRepository.Presence(7, 9));
            when(log.history(ORG, SESSION, "an", 50, 0))
                    .thenReturn(List.of(entry(ticketId, "ACCEPTED"), entry(null, "INVALID_TOKEN")));

            var history = query.forSession(ORG, SESSION, "an", 0, 50);

            assertThat(history.inside()).isEqualTo(7);
            assertThat(history.checkedIn()).isEqualTo(9);
            assertThat(history.rows())
                    .extracting(CheckinHistoryQuery.Row::ticketCode)
                    .containsExactly("ABCDEF12", null);
        }

        private CheckinLogRepository.Entry entry(UUID ticketId, String result) {
            return new CheckinLogRepository.Entry(
                    UUID.randomUUID(),
                    ticketId,
                    NOW,
                    "IN",
                    result,
                    "QR",
                    "Cổng A",
                    null,
                    ACTOR,
                    "Nguyễn Văn An",
                    "A-1",
                    "1",
                    "VIP");
        }
    }

    @Nested
    @DisplayName("Thống kê soát vé")
    class ThongKe {

        private final GateStats.Reader reader = mock(GateStats.Reader.class);
        private final GateStatsQuery query = new GateStatsQuery(reader);

        @Test
        @DisplayName("Khoảng ngày rỗng, ngược hoặc thiếu đầu: 400 VALIDATION_FAILED, không chạm database")
        void khoang_ngay_khong_hop_le() {
            LocalDate day = LocalDate.of(2026, 10, 12);

            assertValidation(() -> query.activity(ORG, null, day, null));
            assertValidation(() -> query.activity(ORG, day, null, null));
            assertValidation(() -> query.activity(ORG, day, day.minusDays(1), null));
            verifyNoInteractions(reader);
        }

        @Test
        @DisplayName("Tối đa 731 ngày tính cả hai đầu: 731 được, 732 bị từ chối")
        void toi_da_731_ngay() {
            LocalDate from = LocalDate.of(2025, 1, 1);
            when(reader.timeline(any(), anyInt())).thenReturn(List.of());

            query.activity(ORG, from, from.plusDays(730), null);

            assertValidation(() -> query.activity(ORG, from, from.plusDays(731), null));
        }

        @Test
        @DisplayName("Quá 200 suất trong một lần hỏi: từ chối")
        void qua_nhieu_suat() {
            LocalDate day = LocalDate.of(2026, 10, 12);
            assertValidation(() -> query.activity(ORG, day, day, uuids(201)));
            verifyNoInteractions(reader);
        }

        @Test
        @DisplayName("Ngày tính theo giờ Việt Nam, khoảng nửa mở [from 00:00, to+1 00:00)")
        void ngay_theo_gio_viet_nam() {
            when(reader.timeline(any(), anyInt())).thenReturn(List.of());

            query.activity(ORG, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), List.of(SESSION));

            ArgumentCaptor<GateStats.LogFilter> filter = ArgumentCaptor.forClass(GateStats.LogFilter.class);
            verify(reader).totals(filter.capture());
            assertThat(filter.getValue().from()).isEqualTo(Instant.parse("2026-10-11T17:00:00Z"));
            assertThat(filter.getValue().to()).isEqualTo(Instant.parse("2026-10-12T17:00:00Z"));
            assertThat(filter.getValue().sessionIds()).containsExactly(SESSION);
            assertThat(filter.getValue().organizationId()).isEqualTo(ORG);
        }

        @Test
        @DisplayName("Dòng thời gian quá 400 ô: cắt về 400 ô mới nhất, báo bị cắt, trả theo thời gian tăng dần")
        void dong_thoi_gian_bi_cat() {
            LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
            List<GateStats.FlowPoint> newestFirst = new ArrayList<>();
            for (int i = 400; i >= 0; i--) {
                newestFirst.add(new GateStats.FlowPoint(start.plusMinutes(15L * i), i, 0, 0));
            }
            when(reader.timeline(any(), eq(401))).thenReturn(newestFirst);

            var activity = query.activity(ORG, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), null);

            assertThat(activity.timelineTruncated()).isTrue();
            assertThat(activity.timeline()).hasSize(400);
            assertThat(activity.timeline().get(0).bucket()).isEqualTo(start.plusMinutes(15));
            assertThat(activity.timeline().get(399).bucket()).isEqualTo(start.plusMinutes(15L * 400));
        }

        @Test
        @DisplayName("Dòng thời gian vừa đủ 400 ô: không báo bị cắt")
        void dong_thoi_gian_vua_du() {
            LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
            List<GateStats.FlowPoint> points = IntStream.range(0, 400)
                    .mapToObj(i -> new GateStats.FlowPoint(start.plusMinutes(15L * i), 1, 0, 0))
                    .toList();
            when(reader.timeline(any(), anyInt())).thenReturn(points);

            var activity = query.activity(ORG, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), null);

            assertThat(activity.timelineTruncated()).isFalse();
            assertThat(activity.timeline()).hasSize(400);
        }

        @Test
        @DisplayName("Tên người mua bị che ngay trong service: giữ họ, các chữ còn lại chỉ còn chữ cái đầu")
        void ten_bi_che() {
            UUID o1 = UUID.randomUUID();
            UUID o2 = UUID.randomUUID();
            UUID o3 = UUID.randomUUID();
            UUID o4 = UUID.randomUUID();
            when(reader.holderNames(ORG, List.of(o1, o2, o3, o4)))
                    .thenReturn(List.of(
                            new GateStats.Buyer(o1, "Nguyễn Văn An"),
                            new GateStats.Buyer(o2, "  đỗ   thị  ánh "),
                            new GateStats.Buyer(o3, "Madonna"),
                            new GateStats.Buyer(o4, "   ")));

            var buyers = query.buyers(ORG, List.of(o1, o2, o3, o4)).buyers();

            assertThat(buyers)
                    .extracting(GateStats.Buyer::name)
                    .containsExactly("Nguyễn V. A.", "đỗ T. Á.", "M.", null);
        }

        @Test
        @DisplayName("Không hỏi đơn nào thì không chạm database; quá 100 đơn thì từ chối")
        void gioi_han_so_don() {
            assertThat(query.buyers(ORG, List.of()).buyers()).isEmpty();
            assertThat(query.buyers(ORG, null).buyers()).isEmpty();
            assertValidation(() -> query.buyers(ORG, uuids(101)));
            verifyNoInteractions(reader);
        }

        @Test
        @DisplayName("Vắng mặt: không nêu suất thì rỗng; quá 200 suất thì từ chối")
        void vang_mat_gioi_han() {
            assertThat(query.noShows(ORG, null).rows()).isEmpty();
            assertValidation(() -> query.noShows(ORG, uuids(201)));
            verifyNoInteractions(reader);
        }
    }

    @Nested
    @DisplayName("Xuất danh sách người mua")
    class XuatDanhSach {

        private final GateStats.Reader reader = mock(GateStats.Reader.class);
        private final ExportAuditPort audit = mock(ExportAuditPort.class);
        private final BuyerExportQuery query = new BuyerExportQuery(reader, audit, CLOCK);

        @Test
        @DisplayName("Ghi nhật ký TRƯỚC khi trả: số dòng, phạm vi và các trường bị xuất; mốc xuất theo đồng hồ")
        void ghi_nhat_ky_roi_tra() {
            when(reader.buyersForExport(ORG, List.of(SESSION), 20_001)).thenReturn(List.of(row()));

            var export = query.export(ORG, ACTOR, List.of(SESSION));

            assertThat(export.rows()).hasSize(1);
            assertThat(export.exportedAt()).isEqualTo(NOW);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
            verify(audit).buyerListExported(eq(ORG), eq(ACTOR), details.capture());
            assertThat(details.getValue())
                    .containsEntry("rows", 1)
                    .containsEntry("sessions", 1)
                    .containsKey("fields");
        }

        @Test
        @DisplayName("Không nêu suất nào: xuất cả tổ chức, nhật ký ghi phạm vi ALL")
        void xuat_ca_to_chuc() {
            when(reader.buyersForExport(ORG, List.of(), 20_001)).thenReturn(List.of());

            query.export(ORG, ACTOR, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
            verify(audit).buyerListExported(eq(ORG), eq(ACTOR), details.capture());
            assertThat(details.getValue()).containsEntry("sessions", "ALL");
        }

        @Test
        @DisplayName("Quá 20.000 vé: từ chối EXPORT_TOO_LARGE, không cắt âm thầm, không ghi nhật ký")
        void qua_tran() {
            when(reader.buyersForExport(any(), anyList(), anyInt())).thenReturn(Collections.nCopies(20_001, row()));

            assertThatThrownBy(() -> query.export(ORG, ACTOR, List.of()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(TicketingErrorCode.EXPORT_TOO_LARGE));
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("Đúng 20.000 vé: vẫn xuất được")
        void dung_tran() {
            when(reader.buyersForExport(any(), anyList(), anyInt())).thenReturn(Collections.nCopies(20_000, row()));

            assertThat(query.export(ORG, ACTOR, List.of()).rows()).hasSize(20_000);
        }

        @Test
        @DisplayName("Không ghi được nhật ký: 503 AUDIT_UNAVAILABLE, không trả dữ liệu")
        void khong_ghi_duoc_nhat_ky() {
            when(reader.buyersForExport(any(), anyList(), anyInt())).thenReturn(List.of(row()));
            doThrow(new ExportAuditPort.AuditUnavailableException("identity sập", null))
                    .when(audit)
                    .buyerListExported(any(), any(), any());

            assertThatThrownBy(() -> query.export(ORG, ACTOR, List.of()))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(TicketingErrorCode.AUDIT_UNAVAILABLE);
                        assertThat(e.errorCode().httpStatus()).isEqualTo(503);
                    });
        }

        @Test
        @DisplayName("Quá 200 suất: từ chối trước khi đọc dữ liệu")
        void qua_nhieu_suat() {
            assertValidation(() -> query.export(ORG, ACTOR, uuids(201)));
            verify(reader, never()).buyersForExport(any(), anyList(), anyInt());
            verifyNoInteractions(audit);
        }

        private GateStats.BuyerExportRow row() {
            return new GateStats.BuyerExportRow("ABCDEF12", "Nguyễn Văn An", SESSION, "VIP", "A", "A-1", "VALID", NOW);
        }
    }

    private static void assertValidation(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                .isEqualTo(ErrorCode.Common.VALIDATION_FAILED));
    }

    private static List<UUID> uuids(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }

    private static Ticket ticket(UUID orderId, UUID userId) {
        return Ticket.rehydrate(
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                SESSION,
                ORG,
                userId,
                null,
                "B-7",
                "B",
                "SEATED",
                "7",
                "Thường",
                null,
                TicketStatus.VALID,
                null,
                null);
    }
}
