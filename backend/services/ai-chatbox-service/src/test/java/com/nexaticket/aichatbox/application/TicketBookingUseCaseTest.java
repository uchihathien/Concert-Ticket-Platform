// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.booking.TicketBookingUseCase;
import com.nexaticket.aichatbox.application.booking.TicketBookingUseCase.BookingOutcome;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.model.TicketHold;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.BookingRejectedException;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.support.FakeCommerce;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Đặt vé qua chat: giữ chỗ rồi đặt đơn, và dọn dẹp khi bước hai hỏng.
 *
 * <p>Không có Spring, không có mạng: ba cổng ra ngoài là hàng giả ghi lại từng lời gọi. Thứ cần
 * kiểm ở đây là <b>thứ tự và điều kiện</b> gọi, không phải HTTP.
 */
class TicketBookingUseCaseTest {

    private final UUID sessionId = UUID.randomUUID();
    private final RecordingCatalog catalog = new RecordingCatalog();
    private final RecordingInventory inventory = new RecordingInventory();
    private final ScriptedOrdering ordering = new ScriptedOrdering();
    private final TicketBookingUseCase booking =
            new TicketBookingUseCase(catalog, inventory, ordering, new FakeCommerce.Credentials(), Duration.ZERO);

    /** Ordering trả lần lượt từng kết cục trong hàng đợi; hết hàng đợi thì như hàng giả gốc. */
    static class ScriptedOrdering extends FakeCommerce.Ordering {
        final Deque<Object> outcomes = new ArrayDeque<>();

        @Override
        public PlacedOrder placeOrder(UUID holdId, String callerAccessToken, String idempotencyKey) {
            Object next = outcomes.poll();
            if (next == null) {
                return super.placeOrder(holdId, callerAccessToken, idempotencyKey);
            }
            placeKeys.add(idempotencyKey);
            placedHolds.add(holdId);
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (PlacedOrder) next;
        }
    }

    /** Ghi lại mã khu inventory nhận được — hàng giả gốc không lưu. */
    static class RecordingInventory extends FakeCommerce.Inventory {
        final List<String> zones = new ArrayList<>();

        @Override
        public TicketHold holdZone(
                UUID eventSessionId,
                String zoneCode,
                int quantity,
                ZoneAdmission admission,
                String callerAccessToken,
                String idempotencyKey) {
            zones.add(zoneCode);
            return super.holdZone(eventSessionId, zoneCode, quantity, admission, callerAccessToken, idempotencyKey);
        }
    }

    static class RecordingCatalog extends FakeCommerce.Catalog {
        final List<String> admissionZones = new ArrayList<>();

        @Override
        public Optional<ZoneAdmission> findZoneAdmission(String slug, String zoneCode) {
            admissionZones.add(zoneCode);
            return super.findZoneAdmission(slug, zoneCode);
        }
    }

    private static RemoteCallException timeout() {
        return new RemoteCallException("ordering-service", "giả: quá hạn", null);
    }

    @BeforeEach
    void seed() {
        catalog.put(FakeCommerce.sampleEvent(sessionId), ZoneAdmission.SEATED);
        catalog.zones.put("dem-nhac-trinh|GA", ZoneAdmission.STANDING);
        ordering.nextOrder = new PlacedOrder(
                UUID.randomUUID(),
                "NT-240009",
                3_600_000L,
                "https://pay.payos.vn/web/abc",
                Instant.now().plus(Duration.ofMinutes(15)));
    }

    @Test
    void giu_cho_roi_dat_don_voi_khoa_chong_trung_sinh_tu_loi_goi_tool() {
        BookingOutcome outcome = booking.initiate("dem-nhac-trinh", sessionId, "A", 2, "call-7");

        assertThat(outcome).isInstanceOf(BookingOutcome.Started.class);
        BookingOutcome.Started started = (BookingOutcome.Started) outcome;
        assertThat(started.order().orderNumber()).isEqualTo("NT-240009");
        assertThat(started.hold().quantity()).isEqualTo(2);
        // Hai khoá khác nhau cho hai endpoint, cùng gốc từ callId: gửi lại cùng quyết định thì
        // không giữ thêm chỗ và không đặt thêm đơn.
        assertThat(inventory.holdKeys).containsExactly("chat-hold-call-7");
        assertThat(ordering.placeKeys).containsExactly("chat-order-call-7");
        assertThat(inventory.released).isEmpty();
    }

    @Test
    void khu_dung_thi_gui_dung_danh_sach_standing() {
        booking.initiate("dem-nhac-trinh", sessionId, "GA", 3, "call-8");

        assertThat(inventory.admissions).containsExactly(ZoneAdmission.STANDING);
    }

    @Test
    void ordering_tu_choi_thi_nha_cho_vua_giu() {
        ordering.placeFailure = new BookingRejectedException("Mã khuyến mãi không hợp lệ.");

        BookingOutcome outcome = booking.initiate("dem-nhac-trinh", sessionId, "A", 1, "call-9");

        assertThat(outcome).isInstanceOf(BookingOutcome.Rejected.class);
        assertThat(((BookingOutcome.Rejected) outcome).reason()).contains("khuyến mãi");
        // Không nhả thì chỗ treo 10 phút và khách thử lại nhận "hết chỗ" do chính mình.
        assertThat(inventory.released).hasSize(1);
    }

    /**
     * Cập nhật theo 6.12 (R-25): bản cũ khẳng định "ordering không phản hồi thì nhả chỗ rồi ném lỗi"
     * — đúng hành vi gây đơn trùng. Quá hạn cả hai lần thì GIỮ chỗ và trả lý do "chưa xác nhận".
     */
    @Test
    void ordering_khong_phan_hoi_hai_lan_thi_giu_cho_va_dan_dung_dat_lai() {
        ordering.placeFailure = timeout();

        BookingOutcome outcome = booking.initiate("dem-nhac-trinh", sessionId, "A", 1, "call-10");

        assertThat(outcome).isInstanceOf(BookingOutcome.Rejected.class);
        String reason = ((BookingOutcome.Rejected) outcome).reason();
        assertThat(reason).isEqualTo(TicketBookingUseCase.UNCONFIRMED_REASON);
        assertThat(reason.toLowerCase(java.util.Locale.ROOT)).contains("đừng");
        assertThat(ordering.placeKeys).containsExactly("chat-order-call-10", "chat-order-call-10");
        assertThat(inventory.released).isEmpty();
    }

    @Test
    void qua_han_roi_hoi_lai_thanh_cong_thi_co_don_va_giu_cho() {
        ordering.outcomes.add(timeout());
        ordering.outcomes.add(ordering.nextOrder);

        BookingOutcome outcome = booking.initiate("dem-nhac-trinh", sessionId, "A", 2, "s1");

        assertThat(outcome).isInstanceOf(BookingOutcome.Started.class);
        assertThat(ordering.placeKeys).containsExactly("chat-order-s1", "chat-order-s1");
        assertThat(ordering.placedHolds).hasSize(2).containsOnly(ordering.placedHolds.getFirst());
        assertThat(inventory.released).isEmpty();
    }

    @Test
    void qua_han_roi_hoi_lai_bi_tu_choi_thi_nha_cho() {
        ordering.outcomes.add(timeout());
        ordering.outcomes.add(new BookingRejectedException("Chỗ giữ đã hết hạn."));

        BookingOutcome outcome = booking.initiate("dem-nhac-trinh", sessionId, "A", 1, "s2");

        assertThat(((BookingOutcome.Rejected) outcome).reason()).isEqualTo("Chỗ giữ đã hết hạn.");
        assertThat(inventory.released).containsExactly(ordering.placedHolds.getFirst());
    }

    @Test
    void qua_han_roi_loi_la_thi_coi_nhu_chua_biet_va_giu_cho() {
        ordering.outcomes.add(timeout());
        ordering.outcomes.add(new IllegalStateException("lỗi lạ"));

        BookingOutcome outcome = booking.initiate("dem-nhac-trinh", sessionId, "A", 1, "s3");

        assertThat(((BookingOutcome.Rejected) outcome).reason()).isEqualTo(TicketBookingUseCase.UNCONFIRMED_REASON);
        assertThat(inventory.released).isEmpty();
    }

    @Test
    void bi_ngat_luc_cho_hoi_lai_thi_giu_cho_va_giu_co_ngat() {
        TicketBookingUseCase slow = new TicketBookingUseCase(
                catalog, inventory, ordering, new FakeCommerce.Credentials(), Duration.ofSeconds(30));
        ordering.outcomes.add(timeout());
        Thread.currentThread().interrupt();
        try {
            BookingOutcome outcome = slow.initiate("dem-nhac-trinh", sessionId, "A", 1, "s4");

            assertThat(((BookingOutcome.Rejected) outcome).reason()).isEqualTo(TicketBookingUseCase.UNCONFIRMED_REASON);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(inventory.released).isEmpty();
            assertThat(ordering.placeKeys).hasSize(1);
        } finally {
            Thread.interrupted();
        }
    }

    /** R-26: mô hình viết "ga", catalog khai "GA" — mọi bên nhận giá trị chuẩn. */
    @Test
    void ma_khu_viet_thuong_thi_gui_ma_chuan_cua_catalog() {
        BookingOutcome outcome = booking.initiate("dem-nhac-trinh", sessionId, "ga", 1, "s5");

        assertThat(outcome).isInstanceOf(BookingOutcome.Started.class);
        assertThat(inventory.zones).containsExactly("GA");
        assertThat(catalog.admissionZones).containsExactly("GA");
        assertThat(inventory.admissions).containsExactly(ZoneAdmission.STANDING);
    }

    @Test
    void inventory_tu_choi_thi_khong_dat_don() {
        inventory.holdFailure = new BookingRejectedException("Khu này không còn đủ chỗ trống.");

        BookingOutcome outcome = booking.initiate("dem-nhac-trinh", sessionId, "A", 4, "call-11");

        assertThat(outcome).isInstanceOf(BookingOutcome.Rejected.class);
        assertThat(ordering.placedHolds).isEmpty();
    }

    @Test
    void suat_khong_thuoc_su_kien_thi_khong_goi_inventory() {
        BookingOutcome outcome = booking.initiate("dem-nhac-trinh", UUID.randomUUID(), "A", 1, "call-12");

        assertThat(outcome).isInstanceOf(BookingOutcome.Rejected.class);
        assertThat(((BookingOutcome.Rejected) outcome).reason()).contains("không thuộc sự kiện");
        assertThat(inventory.holdKeys).isEmpty();
    }

    @Test
    void khu_khong_co_trong_suat_thi_tu_choi_som() {
        BookingOutcome outcome = booking.initiate("dem-nhac-trinh", sessionId, "Z9", 1, "call-13");

        assertThat(((BookingOutcome.Rejected) outcome).reason()).contains("không có khu Z9");
        assertThat(inventory.holdKeys).isEmpty();
    }

    @Test
    void slug_tu_dung_thi_tu_choi_va_chi_cach_sua() {
        BookingOutcome outcome = booking.initiate("dem-nhac-trinh-2026", sessionId, "A", 1, "call-14");

        assertThat(((BookingOutcome.Rejected) outcome).reason()).contains("findEvents");
    }

    @Test
    void so_ve_ngoai_khoang_thi_tu_choi_truoc_moi_loi_goi() {
        assertThat(booking.initiate("dem-nhac-trinh", sessionId, "A", 0, "c"))
                .isInstanceOf(BookingOutcome.Rejected.class);
        assertThat(booking.initiate("dem-nhac-trinh", sessionId, "A", 7, "c"))
                .isInstanceOf(BookingOutcome.Rejected.class);
        assertThat(inventory.holdKeys).isEmpty();
    }
}
