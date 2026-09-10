// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import com.nexaticket.aichatbox.domain.model.ToolSpec;
import com.nexaticket.aichatbox.domain.port.LlmProviderPort;
import com.nexaticket.aichatbox.domain.port.LlmUnavailableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter sang Claude qua SDK chính thức của Anthropic.
 *
 * <p>Toàn bộ hiểu biết về nhà cung cấp dừng ở lớp này. Tầng application chỉ thấy {@link Exchange}
 * và {@link ToolSpec}; đổi sang nhà cung cấp khác là viết một adapter mới, không đụng vòng ReAct.
 *
 * <h2>Ba chi tiết dễ làm sai và không báo lỗi ngay</h2>
 *
 * <ol>
 *   <li><b>Prompt hệ thống được đánh dấu cache.</b> Nó là hằng số và đứng đầu request, nên mọi
 *       lượt của mọi người dùng dùng chung một prefix đã cache. Bỏ dấu này thì hệ thống vẫn chạy
 *       đúng y hệt, chỉ đắt hơn nhiều lần — và không có gì trong log nói ra điều đó.
 *   <li><b>Khối suy luận phải gửi lại nguyên vẹn.</b> Khi mô hình vừa suy luận vừa gọi tool, lượt
 *       trả lời chứa cả khối suy luận có chữ ký. Vòng sau phải gửi lại đúng khối đó; dựng lại từ
 *       danh sách tool call là không thể. Đó là lý do {@code providerEcho} tồn tại.
 *   <li><b>Trả lời hỗn hợp.</b> Một lượt có thể vừa có lời vừa có tool call. Chừng nào còn tool
 *       call thì chưa phải câu trả lời cuối — đọc {@code stopReason} chứ đừng đoán theo việc có
 *       khối text hay không.
 * </ol>
 */
@Component
public class AnthropicLlmAdapter implements LlmProviderPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmAdapter.class);

    private final AnthropicClient client;
    private final AnthropicProperties anthropic;
    private final AgentProperties agent;

    public AnthropicLlmAdapter(AnthropicClient client, AnthropicProperties anthropic, AgentProperties agent) {
        this.client = client;
        this.anthropic = anthropic;
        this.agent = agent;
    }

    @Override
    public LlmTurn complete(LlmRequest request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(agent.model())
                .maxTokens(anthropic.maxTokens())
                // Suy luận thích ứng: mô hình tự quyết định nghĩ bao nhiêu. Tắt hẳn trên dòng
                // Opus 5 có một kiểu hỏng khó chịu — thỉnh thoảng mô hình viết lời gọi tool ra
                // phần văn bản thay vì phát một khối tool_use, và khi đó lời gọi không bao giờ
                // chạy mà cũng không có lỗi nào.
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(effort()).build())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.systemPrompt())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()));

        for (ToolSpec spec : request.tools()) {
            params.addTool(toTool(spec));
        }
        for (Exchange exchange : request.transcript()) {
            params.addMessage(toMessage(exchange));
        }

        Message response;
        try {
            response = client.messages().create(params.build());
        } catch (AnthropicServiceException e) {
            // 429, 5xx, quá hạn mức. Tất cả đều là "thử lại sau", không phải lỗi lập trình.
            throw new LlmUnavailableException("Nhà cung cấp mô hình trả lỗi", e);
        } catch (RuntimeException e) {
            // Quá hạn, DNS hỏng, mạng đứt.
            throw new LlmUnavailableException("Không gọi được nhà cung cấp mô hình", e);
        }

        return interpret(response);
    }

    /**
     * Đọc một lượt trả lời.
     *
     * <p>Phân nhánh theo {@code stopReason}, không theo "có khối text hay không": một lượt vừa có
     * lời dẫn vừa có tool call là bình thường, và coi lời dẫn đó là câu trả lời cuối sẽ khiến agent
     * trả lời khách bằng "để mình tra giúp bạn nhé" rồi dừng lại ở đó.
     */
    private LlmTurn interpret(Message response) {
        List<ToolInvocation> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<ContentBlockParam> echo = new ArrayList<>();

        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> {
                text.append(t.text());
                echo.add(ContentBlockParam.ofText(
                        TextBlockParam.builder().text(t.text()).build()));
            });
            block.thinking()
                    .ifPresent(t -> echo.add(ContentBlockParam.ofThinking(ThinkingBlockParam.builder()
                            .thinking(t.thinking())
                            .signature(t.signature())
                            .build())));
            block.redactedThinking()
                    .ifPresent(t -> echo.add(ContentBlockParam.ofRedactedThinking(
                            RedactedThinkingBlockParam.builder().data(t.data()).build())));
            block.toolUse().ifPresent(t -> {
                calls.add(new ToolInvocation(t.id(), t.name(), asMap(t._input())));
                echo.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(t.id())
                        .name(t.name())
                        .input(t._input())
                        .build()));
            });
        }

        if (!calls.isEmpty()) {
            return new LlmTurn.ToolRequest(calls, echo);
        }
        if (text.isEmpty()) {
            // Không lời, không tool. Xảy ra khi mô hình từ chối trả lời, hoặc khi lượt bị cắt vì
            // chạm trần token trong lúc còn đang suy luận.
            log.warn("Mô hình trả lượt rỗng, stopReason={}", response.stopReason());
            throw new LlmUnavailableException("Mô hình không trả về nội dung nào", null);
        }
        return new LlmTurn.Answer(text.toString());
    }

    private MessageParam toMessage(Exchange exchange) {
        return switch (exchange) {
            case Exchange.UserSaid u -> MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(List.of(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(u.text()).build())))
                    .build();
            case Exchange.AssistantSaid a -> MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(List.of(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(a.text()).build())))
                    .build();
            case Exchange.AssistantRequestedTools t -> MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(echoOf(t))
                    .build();
                // Kết quả tool đi trong message vai USER — đó là quy ước của giao thức, không phải
                // lựa chọn ở đây: "user" nghĩa là "phía gọi", và phía gọi là ta.
            case Exchange.ToolsReturned r -> MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(r.outcomes().stream()
                            .map(AnthropicLlmAdapter::toResultBlock)
                            .toList())
                    .build();
        };
    }

    /**
     * Lấy lại khối nội dung nguyên bản của lượt gọi tool.
     *
     * <p>{@code providerEcho} do chính adapter này tạo ra ở {@link #interpret}, nên ép kiểu là an
     * toàn — nhưng nếu một adapter khác từng chạy trong cùng phiên thì không. Dựng lại từ danh
     * sách tool call là đường lùi duy nhất có thể, và nó mất khối suy luận.
     */
    @SuppressWarnings("unchecked")
    private static List<ContentBlockParam> echoOf(Exchange.AssistantRequestedTools exchange) {
        if (exchange.providerEcho() instanceof List<?> blocks && !blocks.isEmpty()) {
            return (List<ContentBlockParam>) blocks;
        }
        log.warn("Thiếu khối nguyên bản của lượt gọi tool — dựng lại, mất phần suy luận");
        return exchange.calls().stream()
                .map(call -> ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(call.callId())
                        .name(call.toolName())
                        .input(JsonValue.from(call.arguments()))
                        .build()))
                .toList();
    }

    private static ContentBlockParam toResultBlock(ToolOutcome outcome) {
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(outcome.callId())
                .content(outcome.payload())
                // Cờ lỗi là thứ dạy mô hình nói "hệ thống đang bận" thay vì "không tìm thấy".
                .isError(outcome.failed())
                .build());
    }

    /** Dựng JSON Schema từ {@link ToolSpec} — không ai viết schema bằng tay ở đây. */
    private static Tool toTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        List<String> required = new ArrayList<>();
        for (ToolSpec.Param param : spec.params()) {
            properties.putAdditionalProperty(
                    param.name(), JsonValue.from(Map.of("type", param.type(), "description", param.description())));
            if (param.required()) {
                required.add(param.name());
            }
        }
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(required)
                        .build())
                .build();
    }

    /**
     * Tham số mô hình sinh ra, đưa về {@code Map}.
     *
     * <p>Không so khớp chuỗi trên JSON thô: cách thoát ký tự khác nhau giữa các mô hình, và một
     * bộ so chuỗi chạy đúng hôm nay sẽ hỏng lặng lẽ sau một lần đổi mô hình.
     */
    private static Map<String, Object> asMap(JsonValue input) {
        Object converted = input.convert(Object.class);
        if (converted instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private OutputConfig.Effort effort() {
        return OutputConfig.Effort.of(anthropic.effort().toLowerCase());
    }
}
