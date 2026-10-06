// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.knowledge.KnowledgeBaseUseCase;
import com.nexaticket.aichatbox.application.knowledge.KnowledgeViews;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Nạp tri thức nền.
 *
 * <p>Gọi thẳng {@code seed()} thay vì dựng một context có seeder bật: nó chạy trên một luồng ảo ở
 * lúc khởi động, nên khẳng định trên kết quả của nó sẽ là một cuộc đua. Thứ cần kiểm là <b>logic</b>
 * — nạp khi trống, không đụng gì khi đã có — chứ không phải việc Spring có gọi ApplicationRunner hay
 * không.
 */
class KnowledgeSeederIT extends AiChatboxTestBase {

    @Autowired
    KnowledgeBaseUseCase knowledge;

    private List<KnowledgeViews.ChunkRow> everything() {
        return knowledge.listChunks(null, 100, 0);
    }

    @Test
    void kho_trong_thi_nap_day_du_va_tim_lai_duoc() {
        new KnowledgeSeeder(knowledge).seed();

        assertThat(everything()).hasSize(StarterKnowledge.all().size());

        // Không chỉ kiểm "đã ghi vào bảng": phải lấy ra được bằng ĐÚNG đường agent đi, và đúng đoạn
        // phải đứng đầu.
        //
        // Cố ý KHÔNG khẳng định cờ `used`. Bộ nhúng ở test là túi từ (xem FakeAiProviders), nên một
        // câu hỏi bốn từ so với một đoạn sáu mươi từ luôn cho khoảng cách lớn — ở đây là 0,68 trong
        // khi ngưỡng là 0,55 — dù thứ hạng hoàn toàn đúng. Mô hình thật (bge-m3, voyage-3.5) nhúng
        // theo ngữ nghĩa nên không có hiệu ứng đó. Khẳng định `used` ở đây là khẳng định về hàng
        // giả, không phải về hệ thống; câu hỏi "ngưỡng đặt bao nhiêu là đúng" thuộc về người vận
        // hành, và endpoint `preview` tồn tại chính để họ trả lời nó trên dữ liệu thật.
        assertThat(knowledge.preview(null, "giữ chỗ được bao lâu")).first().satisfies(row -> assertThat(row.title())
                .contains("giữ chỗ"));
    }

    @Test
    void kho_da_co_noi_dung_thi_khong_dung_gi() {
        knowledge.addChunk(null, "Đoạn do đội vận hành soạn", "Nội dung riêng của tổ chức này.");

        new KnowledgeSeeder(knowledge).seed();

        // Điều kiện nạp là kho TRỐNG, không phải một cờ "đã chạy". Nhờ vậy người soạn xoá bớt đoạn
        // nào cũng được mà lần khởi động sau không dựng lại chúng.
        assertThat(everything()).hasSize(1);
    }

    @Test
    void chay_hai_lan_khong_nhan_doi_kho() {
        new KnowledgeSeeder(knowledge).seed();
        new KnowledgeSeeder(knowledge).seed();

        assertThat(everything()).hasSize(StarterKnowledge.all().size());
    }

    /**
     * Hai con số trong tri thức nền phải khớp mã nguồn, vì trợ lý sẽ đọc chúng ra như sự thật.
     *
     * <p>Test này không kiểm hành vi mà kiểm <b>tính đúng của nội dung</b> — nó tồn tại để một lần
     * đổi {@code hold_ttl_seconds} hay {@code payment-window} không lặng lẽ biến trợ lý thành nguồn
     * thông tin sai. Đổi hằng số ở kia thì test này đỏ, và đó đúng là lúc cần sửa câu chữ ở đây.
     */
    @Test
    void con_so_trong_tri_thuc_nen_khop_voi_cau_hinh_that() {
        String all = StarterKnowledge.all().stream()
                .map(chunk -> chunk.title() + " " + chunk.content())
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(all).contains("10 phút"); // inventory V0100: hold_ttl_seconds DEFAULT 600
        assertThat(all).contains("15 phút"); // ordering: payment-window: 15m
        assertThat(all).contains("1900 1234"); // khớp SupportAgentPrompts.SYSTEM
    }

    /** Tri thức nền là của NỀN TẢNG, không thuộc sự kiện nào — nếu không nó biến mất khỏi mọi câu hỏi chung. */
    @Test
    void tri_thuc_nen_khong_gan_voi_su_kien_nao() {
        new KnowledgeSeeder(knowledge).seed();

        assertThat(everything()).allSatisfy(row -> assertThat(row.eventId()).isNull());
    }
}
