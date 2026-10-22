// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.port.LlmProviderPort.LlmRequest;
import com.nexaticket.aichatbox.domain.port.LlmProviderPort.LlmTurn;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dịch phản hồi của Messages API — qua SDK thật, tới một máy chủ HTTP giả trên cổng ngẫu nhiên. */
class AnthropicLlmAdapterTest {

    private StubHttpServer server;
    private AnthropicLlmAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttpServer();
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .baseUrl(server.baseUrl())
                .apiKey("test-key")
                .maxRetries(0)
                .build();
        adapter = new AnthropicLlmAdapter(
                client,
                new AnthropicProperties("test-key", null, "low", Duration.ofSeconds(5)),
                new AgentProperties("claude-test", 4, 10, 5, 0.55, 16, 32, 2));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static LlmRequest request() {
        return new LlmRequest("hệ thống", List.of(new Exchange.UserSaid("Đêm nhạc Trịnh?")), List.of());
    }

    private static String message(String content, String stopReason) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-test",
                 "content":%s,"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":42}}
                """
                .formatted(content, stopReason);
    }

    /** Trước đây {@code {"city": null}} làm NPE trong {@code interpret} — ngoài try, thành 500. */
    @Test
    void tham_so_null_bi_bo() {
        server.respond(message(
                "[{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"findEvents\",\"input\":{\"query\":\"Trịnh\",\"city\":null}}]",
                "tool_use"));

        LlmTurn turn = adapter.complete(request());

        assertThat(turn).isInstanceOf(LlmTurn.ToolRequest.class);
        ToolInvocation call = ((LlmTurn.ToolRequest) turn).calls().get(0);
        assertThat(call.stringArg("city")).isNull();
        assertThat(call.stringArg("query")).isEqualTo("Trịnh");
    }

    /** Câu bị cắt giữa chừng không được thành câu trả lời — trả rỗng để vòng ReAct thử lại. */
    @Test
    void cau_bi_cat_vi_max_tokens_tra_rong() {
        server.respond(message("[{\"type\":\"text\",\"text\":\"Sự kiện diễn vào ngày\"}]", "max_tokens"));

        assertThat(adapter.complete(request())).isEqualTo(new LlmTurn.Answer(""));
    }

    @Test
    void goi_tool_cham_tran_van_la_ToolRequest() {
        server.respond(message(
                "[{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"findEvents\",\"input\":{\"query\":\"Trịnh\"}}]",
                "max_tokens"));

        assertThat(adapter.complete(request())).isInstanceOf(LlmTurn.ToolRequest.class);
    }

    @Test
    void cau_tra_loi_binh_thuong_giu_nguyen() {
        server.respond(message("[{\"type\":\"text\",\"text\":\"Xin chào\"}]", "end_turn"));

        assertThat(adapter.complete(request())).isEqualTo(new LlmTurn.Answer("Xin chào"));
    }
}
