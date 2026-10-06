// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.application.booking.TicketBookingUseCase;
import com.nexaticket.aichatbox.application.booking.TicketBookingUseCase.BookingOutcome;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.BookingRejectedException;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.support.FakeCommerce;
import java.time.Duration;
import java.time.Instant;
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
    private final FakeCommerce.Catalog catalog = new FakeCommerce.Catalog();
    private final FakeCommerce.Inventory inventory = new FakeCommerce.Inventory();
    private final FakeCommerce.Ordering ordering = new FakeCommerce.Ordering();
    private final TicketBookingUseCase booking =
            new TicketBookingUseCase(catalog, inventory, ordering, new FakeCommerce.Credentials());

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

    @Test
    void ordering_khong_phan_hoi_thi_nha_cho_roi_bao_loi_len() {
        ordering.placeFailure = new RemoteCallException("ordering-service", "giả: quá hạn", null);

        assertThatThrownBy(() -> booking.initiate("dem-nhac-trinh", sessionId, "A", 1, "call-10"))
                .isInstanceOf(RemoteCallException.class);

        assertThat(inventory.released).hasSize(1);
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
