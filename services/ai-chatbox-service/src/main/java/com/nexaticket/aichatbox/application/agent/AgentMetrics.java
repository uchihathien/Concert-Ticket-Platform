// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Bốn con số về trợ lý, và mỗi con số trả lời một câu hỏi không suy ra được từ cái khác.
 *
 * <h2>Vì sao service này cần metric nghiệp vụ trong khi những service khác thì chưa</h2>
 *
 * <p>Mọi service khác tiêu CPU của ta; service này tiêu tiền theo từng request. Metric hạ tầng
 * (số request, độ trễ HTTP, tỷ lệ 5xx) không nhìn thấy điều đó: một trợ lý gọi mô hình bốn vòng
 * cho mỗi câu hỏi và một trợ lý gọi một vòng trông hoàn toàn giống nhau qua {@code http_server_requests}
 * — cùng số request, cùng mã 200, chỉ khác gấp bốn lần ở hoá đơn cuối tháng.
 *
 * <ul>
 *   <li><b>{@code llm.calls}</b> — số lần gọi mô hình. Đây là đơn vị tính tiền thật, không phải số
 *       request. Chia cho số lượt chat ra chi phí trung bình mỗi câu hỏi.
 *   <li><b>{@code llm.latency}</b> — mô hình chậm bao lâu. Thứ quyết định bao nhiêu luồng bị giữ,
 *       nên nó cũng là metric về khả năng chịu tải chứ không riêng về trải nghiệm.
 *   <li><b>{@code turn.rounds}</b> — số vòng ReAct mỗi lượt. Phân bố dồn về trần
 *       ({@code max-tool-iterations}) nghĩa là mô tả tool đang mơ hồ và mô hình tra lòng vòng —
 *       một vấn đề sửa được bằng cách viết lại mô tả, nhưng chỉ khi biết nó đang xảy ra.
 *   <li><b>{@code handoff.opened}</b> — tỷ lệ chuyển sang người thật, tách theo lý do. Đây là chỉ
 *       số sức khoẻ của trợ lý: {@code LOW_CONFIDENCE} tăng nghĩa là kho tri thức đang hụt,
 *       {@code CUSTOMER_REQUEST} tăng nghĩa là khách không tin nó.
 * </ul>
 *
 * <p>Đặt ở tầng application chứ không trong adapter: "một lượt chat" và "một vòng ReAct" là khái
 * niệm của nghiệp vụ, còn adapter chỉ biết mình vừa gọi một API HTTP.
 */
@Component
public class AgentMetrics {

    private final Timer llmLatency;
    private final Counter llmFailures;
    private final DistributionSummary rounds;
    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.llmLatency = Timer.builder("aichatbox.llm.latency")
                .description("Thời gian một lần gọi mô hình")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        this.llmFailures = Counter.builder("aichatbox.llm.calls")
                .description("Số lần gọi mô hình — đơn vị tính tiền thật")
                .tag("outcome", "failed")
                .register(registry);
        this.rounds = DistributionSummary.builder("aichatbox.turn.rounds")
                .description("Số vòng ReAct của một lượt chat")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
    }

    /** Bọc một lần gọi mô hình: đếm, đo, và phân biệt hỏng với xong. */
    public <T> T recordLlmCall(java.util.function.Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = call.get();
            sample.stop(llmLatency);
            return result;
        } catch (RuntimeException e) {
            sample.stop(llmLatency);
            llmFailures.increment();
            throw e;
        }
    }

    /** Số vòng một lượt chat đã dùng. Ghi cả khi lượt ấy kết thúc bằng chuyển người thật. */
    public void recordRounds(int used) {
        rounds.record(used);
    }

    /** @param trigger lý do chuyển — tách tag để phân biệt "trợ lý hụt" với "khách không tin" */
    public void recordHandoff(String trigger) {
        Counter.builder("aichatbox.handoff.opened")
                .description("Số phiếu chuyển sang người thật")
                .tag("trigger", trigger)
                .register(registry)
                .increment();
    }

    /** @param outcome "ok" hoặc "failed" — tool hỏng nhiều là tin về service được gọi, không về đây */
    public void recordTool(String toolName, String outcome) {
        Counter.builder("aichatbox.tool.calls")
                .description("Số lần agent gọi tool")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
