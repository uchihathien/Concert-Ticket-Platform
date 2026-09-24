// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.model.ToolOutcome;
import com.nexaticket.aichatbox.domain.model.ToolSpec;
import com.nexaticket.aichatbox.domain.port.LlmProviderPort;
import com.nexaticket.aichatbox.domain.port.LlmUnavailableException;
import com.nexaticket.aichatbox.infrastructure.http.PooledHttpFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Mô hình chạy trên hạ tầng của chính mình, qua Ollama.
 *
 * <p>Không có SDK: Ollama là một API HTTP/JSON nhỏ, và kéo về một thư viện client cho bốn trường
 * JSON là thêm một thứ phải nâng cấp. Đổi lại, phần dịch qua lại nằm ngay ở đây và đọc được.
 *
 * <h2>Ba chỗ khác Anthropic mà vòng ReAct không được thấy</h2>
 *
 * <ol>
 *   <li><b>Không có {@code providerEcho}.</b> Ollama không trả khối suy luận cần gửi lại nguyên
 *       vẹn, nên lượt gọi tool dựng lại được đầy đủ từ {@code calls}. Ta vẫn điền
 *       {@code providerEcho} bằng chính danh sách message đã dựng — hợp đồng của
 *       {@code Exchange.AssistantRequestedTools} đòi một giá trị, và để {@code null} thì vòng sau
 *       không phân biệt được "không có" với "đánh rơi".
 *   <li><b>Không có {@code callId}.</b> Ollama ghép kết quả tool theo <b>thứ tự</b>, không theo id.
 *       Ta tự sinh id để phần còn lại của hệ thống không phải biết điều đó, rồi bỏ id đi lúc gửi.
 *   <li><b>Tham số có thể thiếu.</b> Mô hình 7B bỏ sót tham số thường xuyên hơn hẳn. Không sửa ở
 *       đây: tool tự kiểm và trả lỗi, rồi mô hình đọc lỗi đó — đúng đường đã có sẵn.
 * </ol>
 */
@Component
@ConditionalOnProperty(name = LlmProvider.PROPERTY, havingValue = "local", matchIfMissing = true)
public class OllamaLlmAdapter implements LlmProviderPort {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmAdapter.class);

    private final RestClient client;
    private final OllamaProperties properties;

    public OllamaLlmAdapter(RestClient.Builder builder, OllamaProperties properties) {
        this.client = builder.baseUrl(properties.baseUrl())
                .requestFactory(PooledHttpFactory.create(java.time.Duration.ofSeconds(5), properties.timeout()))
                .build();
        this.properties = properties;
    }

    @Override
    public LlmTurn complete(LlmRequest request) {
        List<Map<String, Object>> messages = toMessages(request);

        JsonNode response;
        try {
            response = client.post()
                    .uri("/api/chat")
                    .body(Map.of(
                            "model", properties.chatModel(),
                            "messages", messages,
                            "tools", toTools(request.tools()),
                            // Bắt buộc: mặc định của Ollama là trả từng mẩu (stream). Vòng ReAct
                            // cần trọn một lượt mới quyết định được, nên stream ở đây chỉ làm phần
                            // đọc phức tạp hơn mà không sớm hơn một giây nào.
                            "stream", false,
                            "options", Map.of("temperature", 0.2)))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException e) {
            // Gói lại thành ngoại lệ của domain: tầng application dịch nó thành 503 "trợ lý đang
            // bận". Ollama chưa chạy hoặc chưa pull model đều rơi vào đây, và cả hai đều là "thử
            // lại sau" với khách.
            throw new LlmUnavailableException("Không gọi được Ollama tại " + properties.baseUrl(), e);
        }

        if (response == null || !response.has("message")) {
            throw new LlmUnavailableException("Ollama trả phản hồi không có message", null);
        }
        JsonNode message = response.get("message");

        List<ToolInvocation> calls = toolCalls(message);
        if (!calls.isEmpty()) {
            return new LlmTurn.ToolRequest(calls, messages);
        }

        String text = message.path("content").asText("");
        if (text.isBlank()) {
            // Không lời, không tool. Với mô hình nhỏ thì đây là một kết cục có thật, và trả về một
            // câu rỗng sẽ hiện lên màn hình chat như một bong bóng trắng.
            throw new LlmUnavailableException("Ollama trả lượt rỗng", null);
        }
        return new LlmTurn.Answer(text);
    }

    /** Ollama không có id lời gọi; ta sinh id để phần còn lại của hệ thống không phải biết. */
    private static List<ToolInvocation> toolCalls(JsonNode message) {
        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            return List.of();
        }

        List<ToolInvocation> calls = new ArrayList<>(toolCalls.size());
        for (JsonNode call : toolCalls) {
            JsonNode function = call.path("function");
            Map<String, Object> arguments = new LinkedHashMap<>();
            function.path("arguments")
                    .fields()
                    .forEachRemaining(entry -> arguments.put(
                            entry.getKey(),
                            entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue()));

            calls.add(new ToolInvocation(
                    UUID.randomUUID().toString(), function.path("name").asText(""), arguments));
        }
        return calls;
    }

    /**
     * Dịch bản ghi hội thoại sang định dạng message của Ollama.
     *
     * <p>{@code ToolsReturned} trải thành <b>nhiều</b> message vai {@code tool}, mỗi kết quả một
     * message — Ollama không có khái niệm "một message chứa nhiều kết quả" như Anthropic. Thứ tự
     * phải khớp thứ tự lời gọi ở lượt ngay trước, vì đó là cách duy nhất nó ghép được.
     */
    private List<Map<String, Object>> toMessages(LlmRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", request.systemPrompt()));

        for (Exchange exchange : request.transcript()) {
            switch (exchange) {
                case Exchange.UserSaid user -> messages.add(Map.of("role", "user", "content", user.text()));
                case Exchange.AssistantSaid assistant -> messages.add(
                        Map.of("role", "assistant", "content", assistant.text()));
                case Exchange.AssistantRequestedTools requested -> {
                    List<Map<String, Object>> calls = requested.calls().stream()
                            .map(call -> Map.<String, Object>of(
                                    "function", Map.of("name", call.toolName(), "arguments", call.arguments())))
                            .toList();
                    messages.add(Map.of("role", "assistant", "content", "", "tool_calls", calls));
                }
                case Exchange.ToolsReturned returned -> {
                    for (ToolOutcome outcome : returned.outcomes()) {
                        // Lỗi đi vào cùng một chỗ với kết quả, chỉ khác nội dung: Ollama không có
                        // cờ "is_error". Chuỗi bên dưới là thứ mô hình đọc, nên nó phải nói rõ đây
                        // là lỗi — nếu không mô hình coi thông báo lỗi là dữ liệu.
                        String content = outcome.failed() ? "LỖI: " + outcome.payload() : outcome.payload();
                        messages.add(Map.of("role", "tool", "content", content));
                    }
                }
            }
        }
        return messages;
    }

    /** Dựng JSON Schema từ {@link ToolSpec} — cùng hình dạng OpenAI dùng, Ollama theo chuẩn đó. */
    private static List<Map<String, Object>> toTools(List<ToolSpec> tools) {
        List<Map<String, Object>> out = new ArrayList<>(tools.size());
        for (ToolSpec tool : tools) {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (ToolSpec.Param param : tool.params()) {
                properties.put(param.name(), Map.of("type", param.type(), "description", param.description()));
                if (param.required()) {
                    required.add(param.name());
                }
            }
            out.add(Map.of(
                    "type",
                    "function",
                    "function",
                    Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", Map.of("type", "object", "properties", properties, "required", required))));
        }
        return out;
    }

    /** Ghi một lần lúc khởi động để nhật ký nói rõ đang chạy bằng mô hình nào. */
    @jakarta.annotation.PostConstruct
    void announce() {
        log.info("Trợ lý dùng mô hình local {} qua Ollama tại {}", properties.chatModel(), properties.baseUrl());
    }
}
