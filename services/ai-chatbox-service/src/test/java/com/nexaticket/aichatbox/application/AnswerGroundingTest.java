// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

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

        assertThat(out).contains("chưa thấy sự kiện này");
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
}
