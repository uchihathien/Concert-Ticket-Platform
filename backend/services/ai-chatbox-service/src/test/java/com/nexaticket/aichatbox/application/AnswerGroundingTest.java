// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Chốt chặn "không bịa sự kiện".
 *
 * <p>Gọi qua reflection vì {@code AnswerGrounding} là package-private trong
 * {@code application.agent} — nó là chi tiết nội bộ của vòng ReAct, không phải API. Mở public chỉ để
 * test được là đổi thiết kế theo nhu cầu của test.
 */
class AnswerGroundingTest {

    private static final String NOT_FOUND = "{\"found\":false,\"reason\":\"KHÔNG CÓ SỰ KIỆN NÀO KHỚP\"}";
    private static final String FOUND_EVENT = "{\"found\":true,\"title\":\"Rock Fest\"}";

    private static final boolean KHONG_CO_TRI_THUC = true;
    private static final boolean CO_TRI_THUC = false;

    private static String enforce(String answer, List<ToolOutcome> outcomes, boolean contextEmpty) throws Exception {
        Class<?> type = Class.forName("com.nexaticket.aichatbox.application.agent.AnswerGrounding");
        Method enforce = type.getDeclaredMethod("enforce", String.class, List.class, boolean.class);
        enforce.setAccessible(true);
        return (String) enforce.invoke(null, answer, outcomes, contextEmpty);
    }

    @Test
    void thay_cau_tra_loi_co_ngay_gio_khi_tra_danh_muc_khong_ra_gi() throws Exception {
        String bia = "Concert diễn vào 20:00 ngày 15/11/2026 tại sân Mỹ Đình, vé từ 800.000đ.";

        String out = enforce(bia, List.of(ToolOutcome.ok("c1", NOT_FOUND)), KHONG_CO_TRI_THUC);

        assertThat(out).isNotEqualTo(bia);
        assertThat(out).contains("chưa thấy sự kiện này");
        // Con số bịa phải biến mất hẳn, không phải chỉ được kèm thêm lời cảnh báo.
        assertThat(out).doesNotContain("Mỹ Đình").doesNotContain("800.000đ");
    }

    /**
     * Ca quan trọng nhất, và là ca bản đầu tiên bỏ lọt.
     *
     * <p>Mô hình KHÔNG gọi tool nào rồi tự bịa ba sự kiện từ bộ nhớ của nó. Bản đầu chỉ chặn khi đã
     * tra mà không ra, nên đúng trường hợp tệ nhất này đi qua không ai cản — đo được thật với
     * {@code toolsUsed: []}.
     */
    @Test
    void chan_ca_khi_mo_hinh_khong_goi_tool_nao() throws Exception {
        String bia = "Rock Fest Miền Trung 2023 - 10/10/2023, tại Sân vận động Đà Nẵng.";

        String out = enforce(bia, List.of(), KHONG_CO_TRI_THUC);

        // Lượt không tra danh mục nên câu thay thế là câu chung, không phải "chưa thấy sự kiện này".
        assertThat(out).contains("chưa tra được");
        assertThat(out).doesNotContain("Đà Nẵng");
    }

    @Test
    void giu_nguyen_khi_tool_co_tra_ve_du_lieu() throws Exception {
        String thuc = "Rock Fest diễn 17:00 ngày 23/10/2026, vé từ 450.000đ.";

        assertThat(enforce(thuc, List.of(ToolOutcome.ok("c1", FOUND_EVENT)), KHONG_CO_TRI_THUC))
                .isEqualTo(thuc);
    }

    /**
     * Ranh giới bên kia: câu trả lời dựng từ kho tri thức được phép nêu con số trong tài liệu, vì
     * lúc đó nguồn là đoạn tri thức chứ không phải bộ nhớ mô hình. Chặn oan ở đây thì trợ lý mất khả
     * năng trả lời phần nó làm tốt nhất.
     */
    @Test
    void giu_nguyen_khi_co_doan_tri_thuc_duoc_lay_ra() throws Exception {
        String chinhSach = "Phí xử lý là 5.000đ mỗi vé, thu một lần khi đặt đơn.";

        assertThat(enforce(chinhSach, List.of(), CO_TRI_THUC)).isEqualTo(chinhSach);
    }

    @Test
    void khong_chan_cau_tra_loi_khong_neu_con_so_nao() throws Exception {
        String thanhThat = "Mình chưa thấy sự kiện nào như vậy. Bạn cho mình biết thành phố nhé?";

        assertThat(enforce(thanhThat, List.of(ToolOutcome.ok("c1", NOT_FOUND)), KHONG_CO_TRI_THUC))
                .isEqualTo(thanhThat);
    }

    /** "10 phút" không phải ngày, giờ hay tiền — mẫu nhận dạng cố ý không bắt nó. */
    @Test
    void khong_chan_cau_tra_loi_co_thoi_luong_dang_chu() throws Exception {
        String giuCho = "Thời gian giữ chỗ là 10 phút, màn hình có đồng hồ đếm ngược.";

        assertThat(enforce(giuCho, List.of(), KHONG_CO_TRI_THUC)).isEqualTo(giuCho);
    }

    // --- Văn bản cố định của hệ thống (R-14) ---------------------------------------------------

    private static final String ORDER_NOT_FOUND =
            "{\"found\":false,\"reason\":\"KHÔNG CÓ ĐƠN NÀY TRONG TÀI KHOẢN CỦA KHÁCH\"}";

    private static String reason(String name) throws Exception {
        Class<?> type = Class.forName("com.nexaticket.aichatbox.application.agent.AnswerGrounding");
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    /**
     * Lỗi đo được: chính prompt hệ thống dạy mô hình mời khách gọi hotline "8:00–22:00" khi chưa tra
     * được gì, và "8:00" khớp mẫu giờ — nên câu trả lời an toàn nhất lại bị thay bằng "chưa thấy sự
     * kiện này" cho một khách đang hỏi về ĐƠN HÀNG.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Hệ thống đơn hàng đang bận, bạn thử lại sau ít phút hoặc gọi hotline 1900 1234 (8:00–22:00 hằng ngày).",
                "Hệ thống đơn hàng đang bận, bạn gọi hotline 1900 1234 (8:00 - 22:00) nhé.",
                "Bạn gọi giúp mình hotline (8:00—22:00) số 1900 1234 nhé.",
                "Bạn gọi hotline 1900 1234 (8 : 00-22 : 00) nhé."
            })
    void giu_nguyen_cau_hotline_cua_he_thong(String hotline) throws Exception {
        for (List<ToolOutcome> outcomes : List.of(
                List.<ToolOutcome>of(),
                List.of(ToolOutcome.error("c1", "Hệ thống đơn hàng tạm thời không phản hồi.")),
                List.of(ToolOutcome.ok("c1", ORDER_NOT_FOUND)))) {
            assertThat(enforce(hotline, outcomes, KHONG_CO_TRI_THUC)).isEqualTo(hotline);
        }
    }

    /** Chỉ bỏ văn bản của hệ thống: một con số khác không có nguồn vẫn bị chặn. */
    @Test
    void cau_hotline_kem_so_tien_bia_van_bi_chan() throws Exception {
        String bia = "Đơn của bạn 950.000đ, gọi 1900 1234 (8:00–22:00) nếu cần.";

        String out = enforce(bia, List.of(), KHONG_CO_TRI_THUC);

        assertThat(out).isNotEqualTo(bia).doesNotContain("950.000đ");
    }

    /** "22:00" đứng một mình không phải khung giờ hotline — vẫn dò như giờ bịa. */
    @Test
    void gio_le_khong_phai_khung_hotline_van_bi_do() throws Exception {
        String bia = "Concert bắt đầu lúc 22:00, bạn tới sớm nhé.";

        assertThat(enforce(bia, List.of(), KHONG_CO_TRI_THUC)).isNotEqualTo(bia);
    }

    /**
     * Kết quả không có khoá {@code found} vẫn là dữ liệu: lý do từ chối hoàn vé hay đặt vé là của hệ
     * thống, không phải của bộ nhớ mô hình.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"opened\":false,\"refundable\":false,\"reason\":\"Sự kiện không nhận hoàn vé.\"}",
                "{\"booked\":false,\"reason\":\"Khu A đã hết chỗ.\"}",
                "{\"booked\":true,\"orderNumber\":\"NT-240001\",\"totalVnd\":950000}"
            })
    void ket_qua_tu_choi_hay_dat_ve_la_du_lieu(String payload) throws Exception {
        String giaiThich = "Đơn NT-240001 (950.000đ) không được hoàn vì sự kiện không nhận hoàn vé.";

        assertThat(enforce(giaiThich, List.of(ToolOutcome.ok("c1", payload)), KHONG_CO_TRI_THUC))
                .isEqualTo(giaiThich);
    }

    @Test
    void tra_danh_muc_khong_ra_thi_cau_thay_the_noi_ve_su_kien() throws Exception {
        String payload = "{\"found\":false,\"reason\":\"" + reason("REASON_NO_EVENT_FOR_SLUG") + "\",\"hint\":\"…\"}";

        String out =
                enforce("Đêm nhạc diễn 20:00 ngày 4/10.", List.of(ToolOutcome.ok("c1", payload)), KHONG_CO_TRI_THUC);

        assertThat(out).contains("chưa thấy sự kiện này");
    }

    @Test
    void tra_don_khong_ra_thi_cau_thay_the_khong_nhac_su_kien() throws Exception {
        String out = enforce(
                "Đơn của bạn thanh toán lúc 14:30 ngày 3/10.",
                List.of(ToolOutcome.ok("c1", ORDER_NOT_FOUND)),
                KHONG_CO_TRI_THUC);

        assertThat(out).contains("chưa tra được").doesNotContain("sự kiện");
    }

    /** Hằng trong test viết lại đúng chuỗi của {@code ToolDispatcher} — lệch thì test này đỏ. */
    @Test
    void hang_ly_do_khop_payload_cua_tool() throws Exception {
        assertThat(NOT_FOUND).contains("\"reason\":\"" + reason("REASON_NO_EVENT_MATCH") + "\"");
        assertThat(reason("REASON_MISSING_SLUG")).isEqualTo("THIẾU SLUG");
    }

    @Test
    void cau_null_hoac_rong_tra_nguyen() throws Exception {
        assertThat(enforce(null, List.of(), KHONG_CO_TRI_THUC)).isNull();
        assertThat(enforce("  ", List.of(), KHONG_CO_TRI_THUC)).isEqualTo("  ");
    }
}
