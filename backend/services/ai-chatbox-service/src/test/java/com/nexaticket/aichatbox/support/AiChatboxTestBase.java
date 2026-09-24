// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.nexaticket.platform.test.PostgresSingleton;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
@SpringBootTest
@Import(FakeAiProviders.class)
public abstract class AiChatboxTestBase {

    private static final PostgreSQLContainer<?> POSTGRES =
            PostgresSingleton.forDatabase("ai_chatbox_db", "pgvector/pgvector:pg16", "vector");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSingleton.bind(registry, POSTGRES);
    }
}
