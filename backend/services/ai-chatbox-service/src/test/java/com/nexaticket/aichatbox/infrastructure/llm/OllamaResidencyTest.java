// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.domain.model.Exchange;
import com.nexaticket.aichatbox.domain.port.LlmProviderPort.LlmRequest;
import com.nexaticket.aichatbox.infrastructure.embedding.OllamaEmbeddingAdapter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Hai tham số giữ mô hình chat nằm yên trong GPU: {@code keep_alive} ở cả hai lời gọi, và
 * {@code num_gpu} cho mô hình nhúng. Đo trên máy 4 GB VRAM: không có chúng, mỗi lượt chat Ollama dỡ
 * mô hình chat để nhúng câu hỏi rồi nạp lại — 10–60 giây một lượt.
 */
class OllamaResidencyTest {

    private final ObjectMapper json = new ObjectMapper();
    private StubHttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttpServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private OllamaProperties properties(Duration keepAlive, int embeddingGpuLayers) {
        return new OllamaProperties(
                server.baseUrl(),
                "qwen2.5:7b-instruct",
                "bge-m3",
                3,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                320,
                8192,
                keepAlive,
                embeddingGpuLayers);
    }

    @Test
    void mac_dinh_giu_30_phut_va_nhung_tren_cpu() {
        OllamaProperties p = properties(null, 0);

        assertThat(p.keepAlive()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.keepAliveSeconds()).isEqualTo(1800);
        assertThat(p.embeddingGpuLayers()).isZero();
        assertThat(properties(Duration.ZERO, -7).keepAlive()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties(null, -7).embeddingGpuLayers()).isEqualTo(-1);
    }

    @Test
    void loi_goi_chat_mang_keep_alive() throws Exception {
        server.respond("{\"message\":{\"role\":\"assistant\",\"content\":\"Xin chào\"}}");
        OllamaLlmAdapter adapter = new OllamaLlmAdapter(RestClient.builder(), properties(Duration.ofMinutes(45), 0));

        adapter.complete(new LlmRequest("hệ thống", List.of(new Exchange.UserSaid("hỏi")), List.of()));

        JsonNode body = json.readTree(server.requestBodies.get(0));
        assertThat(body.path("keep_alive").asLong()).isEqualTo(45 * 60);
    }

    @Test
    void loi_goi_nhung_mang_keep_alive_va_num_gpu_0() throws Exception {
        server.respond("{\"embeddings\":[[0.1,0.2,0.3]]}");
        OllamaEmbeddingAdapter adapter = new OllamaEmbeddingAdapter(RestClient.builder(), properties(null, 0));

        adapter.embedQuery("giữ chỗ bao lâu");

        JsonNode body = json.readTree(server.requestBodies.get(0));
        assertThat(body.path("model").asText()).isEqualTo("bge-m3");
        assertThat(body.path("keep_alive").asLong()).isEqualTo(1800);
        assertThat(body.path("options").path("num_gpu").asInt()).isZero();
    }

    /** -1 = để Ollama tự quyết: KHÔNG gửi `options.num_gpu`, vì gửi -1 không phải "tự quyết" với mọi bản Ollama. */
    @Test
    void tu_quyet_thi_khong_gui_num_gpu() throws Exception {
        server.respond("{\"embeddings\":[[0.1,0.2,0.3]]}");
        OllamaEmbeddingAdapter adapter = new OllamaEmbeddingAdapter(RestClient.builder(), properties(null, -1));

        adapter.embedQuery("x");

        JsonNode body = json.readTree(server.requestBodies.get(0));
        assertThat(body.has("options")).isFalse();
        assertThat(body.path("keep_alive").asLong()).isEqualTo(1800);
    }
}
