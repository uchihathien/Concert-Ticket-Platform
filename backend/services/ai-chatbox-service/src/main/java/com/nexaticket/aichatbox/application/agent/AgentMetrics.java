// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import com.nexaticket.aichatbox.domain.model.BookingProposalStatus;
import com.nexaticket.aichatbox.domain.model.ToolSpec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Năm con số về trợ lý, và mỗi con số trả lời một câu hỏi không suy ra được từ cái khác.
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
 *   <li><b>{@code proposals}</b> — đề xuất đặt vé theo trạng thái kết thúc. {@code status=PROPOSED}
 *       là tổng số đề xuất từng được lập (mọi đề xuất đều từng PROPOSED); {@code CONFIRMED},
 *       {@code EXPIRED}, {@code CANCELLED} là số đã kết thúc ở đó. "Đề xuất → xác nhận" đo được tách
 *       khỏi "đặt thành công". Là ảnh chụp theo bảng do {@code ProposalExpiryJob} ghi mỗi phút —
 *       đúng tuyệt đối theo dữ liệu, chậm nhất một phút; xoá dòng cũ (retention) làm số giảm, Prometheus
 *       coi là counter reset.
 * </ul>
 *
 * <p>Đặt ở tầng application chứ không trong adapter: "một lượt chat" và "một vòng ReAct" là khái
 * niệm của nghiệp vụ, còn adapter chỉ biết mình vừa gọi một API HTTP.
 */
@Component
public class AgentMetrics {

    /**
     * Tag {@code tool} chỉ nhận tên có trong danh mục; tên khác gom về {@code unknown}.
     *
     * <p>Tên tool do MÔ HÌNH sinh. Dùng thẳng làm tag thì mỗi tên bịa (mô hình nhỏ, hoặc khách dụ
     * mô hình) là một series Prometheus mới — số series không giới hạn là cách làm sập bộ giám sát.
     */
    private static final Set<String> KNOWN_TOOLS =
            SupportAgentTools.all().stream().map(ToolSpec::name).collect(Collectors.toUnmodifiableSet());

    private final Timer llmLatency;
    private final Counter llmCalls;
    private final Counter llmFailures;
    private final DistributionSummary rounds;
    private final Map<BookingProposalStatus, AtomicLong> proposalTotals = new EnumMap<>(BookingProposalStatus.class);
    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.llmLatency = Timer.builder("aichatbox.llm.latency")
                .description("Thời gian một lần gọi mô hình")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        // Cả hai kết cục. Bản trước chỉ đếm `failed`, nên "đơn vị tính tiền thật" không đếm đúng thứ
        // được tính tiền.
        this.llmCalls = Counter.builder("aichatbox.llm.calls")
                .description("Số lần gọi mô hình — đơn vị tính tiền thật")
                .tag("outcome", "ok")
                .register(registry);
        this.llmFailures = Counter.builder("aichatbox.llm.calls")
                .description("Số lần gọi mô hình — đơn vị tính tiền thật")
                .tag("outcome", "failed")
                .register(registry);
        this.rounds = DistributionSummary.builder("aichatbox.turn.rounds")
                .description("Số vòng ReAct của một lượt chat")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
        // Bốn series có mặt từ lúc khởi động (giá trị 0) — dashboard không phải đợi job chạy lần đầu.
        for (BookingProposalStatus status : BookingProposalStatus.values()) {
            AtomicLong total = new AtomicLong();
            proposalTotals.put(status, total);
            FunctionCounter.builder("aichatbox.proposals", total, AtomicLong::get)
                    .tag("status", status.name())
                    .description("Đề xuất đặt vé: PROPOSED = tổng từng lập; CONFIRMED/EXPIRED/CANCELLED = đã kết "
                            + "thúc ở trạng thái ấy (ảnh chụp theo bảng, cập nhật mỗi phút)")
                    .register(registry);
        }
    }

    /**
     * Ảnh chụp {@code countByStatus()} của bảng đề xuất → bốn series. {@code PROPOSED} = tổng mọi
     * trạng thái (mọi đề xuất đều từng PROPOSED), ba giá trị kia đọc đúng khoá.
     */
    public void updateProposalTotals(Map<BookingProposalStatus, Long> counts) {
        long all = counts.values().stream().mapToLong(Long::longValue).sum();
        proposalTotals.get(BookingProposalStatus.PROPOSED).set(all);
        for (BookingProposalStatus status : BookingProposalStatus.values()) {
            if (status != BookingProposalStatus.PROPOSED) {
                proposalTotals.get(status).set(counts.getOrDefault(status, 0L));
            }
        }
    }

    /** Bọc một lần gọi mô hình: đếm, đo, và phân biệt hỏng với xong. */
    public <T> T recordLlmCall(java.util.function.Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = call.get();
            sample.stop(llmLatency);
            llmCalls.increment();
            return result;
        } catch (RuntimeException e) {
            sample.stop(llmLatency);
            llmFailures.increment();
            throw e;
        }
    }

    /** Số vòng một lượt chat đã dùng. Ghi cả khi lượt ấy kết thúc bằng chuyển người thật. */
    /**
     * Một câu "đã đặt vé" bị chặn vì không có đơn thật (story 1.9). Tỷ lệ này tăng là tín hiệu đổi
     * model/prompt — cùng nhóm cảnh báo với {@code LOW_CONFIDENCE}. Tag {@code reason} chỉ có hai giá trị.
     */
    public void recordBookingClaimBlocked(String reason) {
        Counter.builder("aichatbox.booking_claim.blocked")
                .description("Câu trả lời khẳng định đã đặt vé bị chặn vì không có đơn thật")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /**
     * Một câu trả lời hỏng hình dạng ({@code AnswerShapeGuard}): {@code outcome=retried} khi được trả lại
     * cho mô hình sửa, {@code replaced} khi lượt sửa vẫn hỏng và khách nhận câu an toàn. Tỷ lệ
     * {@code replaced} cao là tín hiệu đổi model, không phải sửa prompt thêm nữa.
     */
    public void recordAnswerShapeRejected(String reason, String outcome) {
        Counter.builder("aichatbox.answer_shape.rejected")
                .description("Câu trả lời còn ô trống, lẫn chữ Hán hoặc nói về sự kiện chưa tra")
                .tag("reason", reason)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void recordRounds(int used) {
        rounds.record(used);
    }

    /**
     * @param trigger lý do chuyển — tách tag để phân biệt "trợ lý hụt" với "khách không tin"
     * @param intent khách cần gì — tách tag để biết bàn hỗ trợ đang nhận việc gì nhiều nhất: REFUND
     *     tăng là chuyện chính sách, INCIDENT tăng là chuyện của ticketing hay payment
     */
    public void recordHandoff(String trigger, String intent) {
        Counter.builder("aichatbox.handoff.opened")
                .description("Số phiếu chuyển sang người thật")
                .tag("trigger", trigger)
                .tag("intent", intent)
                .register(registry)
                .increment();
    }

    /** @param outcome "ok" hoặc "failed" — tool hỏng nhiều là tin về service được gọi, không về đây */
    public void recordTool(String toolName, String outcome) {
        Counter.builder("aichatbox.tool.calls")
                .description("Số lần agent gọi tool")
                .tag("tool", KNOWN_TOOLS.contains(toolName) ? toolName : "unknown")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
