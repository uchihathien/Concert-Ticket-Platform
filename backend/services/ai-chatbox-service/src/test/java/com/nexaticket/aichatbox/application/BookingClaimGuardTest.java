// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.BookingClaimGuard;
import com.nexaticket.aichatbox.application.agent.BookingClaimGuard.Blocked;
import com.nexaticket.aichatbox.application.agent.BookingClaimGuard.Pass;
import com.nexaticket.aichatbox.application.agent.BookingClaimGuard.Verdict;
import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Chốt chặn "đã đặt vé": số đơn phải có nguồn, lời khẳng định phải có đơn thật (story 1.9). */
class BookingClaimGuardTest {

    private static final String REAL = "NT-260925-FSQM90";
    private static final String FAKE_UUID = "3fa85f64-25f0-4226-b435-461969d423a6";

    private static ToolOutcome ok(String payload) {
        return ToolOutcome.ok("c1", payload);
    }

    private static String reason(Verdict v) {
        assertThat(v).isInstanceOf(Blocked.class);
        return ((Blocked) v).reason();
    }

    @Test
    void uuid_khong_co_nguon_bi_chan() {
        Verdict v = BookingClaimGuard.check(
                "Đã đặt 2 vé. Số đơn của bạn là " + FAKE_UUID + ", quét mã VietQR trên màn hình.",
                List.of(ok("{\"found\":true,\"title\":\"Đêm nhạc Trịnh\"}")),
                List.of());

        assertThat(reason(v)).isEqualTo(BookingClaimGuard.UNSOURCED_ORDER_REF);
    }

    @Test
    void khang_dinh_da_dat_khong_co_don_bi_chan() {
        Verdict v =
                BookingClaimGuard.check("Đã đặt 2 vé VIP cho bạn, thanh toán trước 20:00 nhé.", List.of(), List.of());

        assertThat(reason(v)).isEqualTo(BookingClaimGuard.UNBACKED_BOOKING_CLAIM);
    }

    @Test
    void so_don_tu_tool_cua_luot_di_qua() {
        Verdict v = BookingClaimGuard.check(
                "Đơn " + REAL + " của bạn đã thanh toán, vé nằm trong ví vé.",
                List.of(ok("{\"orderNumber\":\"" + REAL + "\",\"status\":\"PAID\"}")),
                List.of());

        assertThat(v).isInstanceOf(Pass.class);
    }

    @Test
    void so_don_tu_luot_tro_ly_da_luu_di_qua_khong_phan_biet_hoa_thuong() {
        Verdict v = BookingClaimGuard.check(
                "Bạn thanh toán đơn nt-260925-fsqm90 trước 20:00 nhé.",
                List.of(),
                List.of(
                        new Exchange.UserSaid("đặt 2 vé"),
                        new Exchange.AssistantSaid(
                                "Đã tạo đơn " + REAL + " — tổng 3.600.000đ, thanh toán trước 20:00.")));

        assertThat(v).isInstanceOf(Pass.class);
    }

    @Test
    void so_don_chi_co_trong_cau_khach_hoac_tool_loi_khong_phai_nguon() {
        Verdict fromUser = BookingClaimGuard.check(
                "Đơn " + REAL + " đã thanh toán.",
                List.of(),
                List.of(new Exchange.UserSaid("đơn " + REAL + " sao rồi")));
        Verdict fromFailed = BookingClaimGuard.check(
                "Đơn " + REAL + " đã thanh toán.", List.of(ToolOutcome.error("c1", "lỗi " + REAL)), List.of());

        assertThat(reason(fromUser)).isEqualTo(BookingClaimGuard.UNSOURCED_ORDER_REF);
        assertThat(reason(fromFailed)).isEqualTo(BookingClaimGuard.UNSOURCED_ORDER_REF);
    }

    @Test
    void cau_phu_dinh_va_cau_chinh_sach_di_qua() {
        assertThat(BookingClaimGuard.check(
                        "Mình chưa đặt vé nào cho bạn. Bạn bấm Xác nhận trên thẻ nhé.", List.of(), List.of()))
                .isInstanceOf(Pass.class);
        assertThat(BookingClaimGuard.check(
                        "Mình không đặt được vé lúc này, bạn thử lại sau nhé.", List.of(), List.of()))
                .isInstanceOf(Pass.class);
        assertThat(BookingClaimGuard.check(
                        "Chỗ giữ 10 phút kể từ lúc chọn, hết giờ thì chỗ được trả lại.", List.of(), List.of()))
                .isInstanceOf(Pass.class);
    }

    @Test
    void khang_dinh_di_qua_khi_luot_co_booked_true() {
        Verdict v = BookingClaimGuard.check(
                "Đã đặt 2 vé, bạn thanh toán theo đường dẫn nhé.",
                List.of(ok("{\"booked\":true,\"orderNumber\":\"" + REAL + "\"}")),
                List.of());

        assertThat(v).isInstanceOf(Pass.class);
    }

    @Test
    void phu_dinh_chi_tinh_trong_ba_tu_truoc_cum() {
        // "không" ở xa phía trước không cứu được một khẳng định rõ ràng.
        Verdict v = BookingClaimGuard.check(
                "Không sao đâu, mình vừa đặt thành công 2 vé cho bạn rồi.", List.of(), List.of());

        assertThat(reason(v)).isEqualTo(BookingClaimGuard.UNBACKED_BOOKING_CLAIM);
    }

    @Test
    void cau_rong_hoac_null_di_qua() {
        assertThat(BookingClaimGuard.check(null, List.of(), List.of())).isInstanceOf(Pass.class);
        assertThat(BookingClaimGuard.check("  ", List.of(), List.of())).isInstanceOf(Pass.class);
    }
}
