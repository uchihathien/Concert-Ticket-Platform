// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.port.LlmProviderPort.LlmRequest;
import com.nexaticket.aichatbox.domain.port.LlmProviderPort.LlmTurn;
import com.nexaticket.aichatbox.domain.port.LlmUnavailableException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OllamaLlmAdapterTest {

    private final ObjectMapper json = new ObjectMapper();
    private StubHttpServer server;
    private OllamaLlmAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttpServer();
        adapter = new OllamaLlmAdapter(
                RestClient.builder(),
                new OllamaProperties(
                        server.baseUrl(),
                        "qwen2.5:7b-instruct",
                        "bge-m3",
                        1024,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        320,
                        8192));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static LlmRequest request() {
        return new LlmRequest("hệ thống", List.of(new Exchange.UserSaid("Đêm nhạc Trịnh?")), List.of());
    }

    @Test
    void gui_num_ctx_trong_options() throws Exception {
        server.respond("{\"message\":{\"role\":\"assistant\",\"content\":\"Xin chào\"}}");

        adapter.complete(request());

        JsonNode options = json.readTree(server.requestBodies.get(0)).path("options");
        assertThat(options.path("num_ctx").asInt()).isEqualTo(8192);
        assertThat(options.path("num_predict").asInt()).isEqualTo(320);
    }

    /**
     * {@code NullNode} trước đây thành chuỗi {@code "null"} — {@code findEvents(city="null")} không ra
     * gì và mô hình nói "chưa bán sự kiện này". {@code 2.0} thì {@code parseInt} hỏng thành "thiếu số
     * lượng".
     */
    @Test
    void tham_so_null_bi_bo_va_so_thuc_thanh_so_nguyen() {
        server.respond(
                """
                {"message":{"role":"assistant","content":"","tool_calls":[{"function":{
                  "name":"initiateTicketBooking",
                  "arguments":{"query":"Trịnh","city":null,"quantity":2.0,"price":1.5,"vip":true,
                               "seats":["A1","A2"],"meta":{"k":"v"}}}}]}}
                """);

        LlmTurn turn = adapter.complete(request());

        assertThat(turn).isInstanceOf(LlmTurn.ToolRequest.class);
        ToolInvocation call = ((LlmTurn.ToolRequest) turn).calls().get(0);
        assertThat(call.stringArg("city")).isNull();
        assertThat(call.arguments()).doesNotContainKey("city");
        assertThat(call.intArg("quantity")).isEqualTo(2);
        assertThat(call.arguments().get("quantity")).isEqualTo(2L);
        assertThat(call.arguments().get("price")).isEqualTo(1.5);
        assertThat(call.arguments().get("vip")).isEqualTo(true);
        assertThat(call.arguments().get("seats")).isEqualTo(List.of("A1", "A2"));
        assertThat(call.stringArg("query")).isEqualTo("Trịnh");
    }

    /** Hình dạng lạ không được thoát ra thành 500 — phải là "trợ lý không dùng được". */
    @Test
    void phan_hoi_hinh_dang_la_thanh_LlmUnavailable() {
        server.respond("{\"message\":\"không phải object\"}");

        assertThatThrownBy(() -> adapter.complete(request())).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void cham_tran_ngu_canh_van_tra_cau_tra_loi() {
        server.respond("{\"prompt_eval_count\":8000,\"message\":{\"role\":\"assistant\",\"content\":\"Được\"}}");

        assertThat(adapter.complete(request())).isEqualTo(new LlmTurn.Answer("Được"));
    }
}
