// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tham số vận hành của agent hỗ trợ.
 *
 * @param maxToolIterations số vòng ReAct tối đa. Hai tool, mỗi tool tra một lần, cộng một vòng dự
 *     phòng khi mô hình cần tra tiếp — 4 là đủ rộng. Nới ra thì mỗi vòng thêm là một lần gọi API
 *     nữa cho cùng một câu hỏi.
 * @param historyTurns số lượt cũ đưa vào prompt. Nhiều hơn không làm agent thông minh hơn, chỉ làm
 *     mỗi request đắt hơn và đẩy phần đầu hội thoại ra khỏi tầm chú ý.
 * @param retrievalTopK số đoạn tri thức lấy về
 * @param maxRetrievalDistance ngưỡng khoảng cách cosine để giữ một đoạn. pgvector luôn trả đủ
 *     {@code topK} kể cả khi không có gì liên quan; không cắt theo ngưỡng thì những đoạn lạc đề đi
 *     thẳng vào prompt và trở thành nguyên liệu để mô hình bịa.
 */
@ConfigurationProperties(prefix = "nexaticket.aichatbox.agent")
public record AgentProperties(
        String model, int maxToolIterations, int historyTurns, int retrievalTopK, double maxRetrievalDistance) {

    public AgentProperties {
        if (model == null || model.isBlank()) {
            model = "claude-opus-5";
        }
        if (maxToolIterations <= 0) {
            maxToolIterations = 4;
        }
        if (historyTurns <= 0) {
            historyTurns = 10;
        }
        if (retrievalTopK <= 0) {
            retrievalTopK = 5;
        }
        if (maxRetrievalDistance <= 0) {
            maxRetrievalDistance = 0.55;
        }
    }
}
