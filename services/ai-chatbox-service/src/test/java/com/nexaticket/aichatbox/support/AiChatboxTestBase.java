// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.nexaticket.platform.test.PostgresSingleton;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Nền cho integration test của ai-chatbox: PostgreSQL + pgvector thật, mô hình là hàng giả.
 *
 * <h2>Vì sao PHẢI là database thật</h2>
 *
 * <p>Gần hết những gì service này hứa đều nằm trong SQL, không nằm trong Java: khoá ngoại từ phiếu
 * sang phiên, unique index bộ phận cho "một phiếu mở mỗi phiên", {@code UPDATE ... WHERE status =
 * 'WAITING'} làm việc nhận phiếu nguyên tử, cặp ràng buộc CHECK giữa trạng thái và mốc thời gian,
 * cờ {@code published} chặn bản nháp, và toán tử {@code <=>} của pgvector. Mock repository thì test
 * xanh trong khi tất cả những thứ trên chưa từng được chạy — đúng chỗ đã để lọt một lỗi 500 ở lượt
 * chat đầu tiên.
 *
 * <h2>Ảnh Docker phải là bản có pgvector</h2>
 *
 * <p>{@code postgres:16-alpine} không có extension {@code vector}, và migration V0100 cố ý không tạo
 * nó (xem ghi chú trong chính file đó). Nên ở đây khai ảnh khác và nhờ {@code PostgresSingleton} tạo
 * extension bằng superuser của container.
 *
 * <h2>Mô hình là hàng giả, và đó không phải sự đánh đổi</h2>
 *
 * <p>Gọi mô hình thật trong test nghĩa là test cần mạng, cần khoá trả phí, và cho kết quả khác nhau
 * giữa hai lần chạy — ba thứ đều biến một test hỏng thành một test không ai tin. Thứ cần kiểm ở đây
 * là <b>vòng ReAct và những gì ghi xuống database</b>, và cả hai đều điều khiển được bằng
 * {@link FakeAiProviders}.
 */
// Tắt nạp tri thức nền: seeder chạy lúc khởi động context sẽ đổ 14 đoạn vào database dùng chung
// của mọi lớp test, và mọi phép khẳng định về nội dung kho sẽ đo phải dữ liệu của nó thay vì của
// chính test. KnowledgeSeederIT bật lại bằng cách gọi thẳng seeder.
@SpringBootTest(properties = "nexaticket.aichatbox.starter-knowledge.enabled=false")
@Import(FakeAiProviders.class)
public abstract class AiChatboxTestBase {

    private static final PostgreSQLContainer<?> POSTGRES =
            PostgresSingleton.forDatabase("ai_chatbox_db", "pgvector/pgvector:pg16", "vector");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }

    @Autowired
    private JdbcClient jdbc;

    /**
     * Bàn sạch trước mỗi ca — cùng quy ước với {@code CatalogTestBase}.
     *
     * <p>Container sống hết vòng đời JVM và mọi lớp test dùng chung một database, nên không dọn thì
     * mỗi test chạy trên đống dữ liệu của những test trước. Kiểu hỏng của nó rất khó chịu: test
     * không sai ngay mà sai khi bộ test <b>lớn lên</b> — hàng đợi phiếu vượt quá {@code limit 50} và
     * một khẳng định về thứ tự bỗng đỏ, trong khi mã nguồn nó kiểm không hề đổi. Đó là loại đỏ khiến
     * người ta nới lỏng khẳng định thay vì sửa nguyên nhân.
     *
     * <p>{@code chat_sessions} kéo theo {@code chat_messages} và {@code chat_handoffs} bằng CASCADE,
     * nhưng cứ liệt kê đủ: một bảng bị quên sẽ lộ ra dưới dạng một test khác đỏ ở chỗ không liên quan.
     */
    @BeforeEach
    void resetChatbox() {
        jdbc.sql("TRUNCATE chat_handoffs, chat_messages, chat_sessions, "
                        + "event_knowledge_embeddings, event_rules, idempotency_records CASCADE")
                .update();
    }
}
