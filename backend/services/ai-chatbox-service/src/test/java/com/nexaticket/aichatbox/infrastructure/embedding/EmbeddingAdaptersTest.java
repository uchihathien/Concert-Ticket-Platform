// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.infrastructure.llm.OllamaProperties;
import com.nexaticket.aichatbox.support.StubHttpServer;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Hai bộ nhúng (Voyage, Ollama) và bộ nhúng tắt, trên máy chủ HTTP cục bộ. */
@DisplayName("Bộ nhúng")
class EmbeddingAdaptersTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VOYAGE_KEY = "pa-KHOA-VOYAGE-KHONG-DUOC-LO";

    private final StubHttpServer server = new StubHttpServer();

    @AfterEach
    void stopServer() {
        server.close();
    }

    private static String vectorOf(int size) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            sb.append(i == 0 ? "" : ",").append(i % 7 == 0 ? "0.5" : "0.25");
        }
        return sb.append(']').toString();
    }

    @Nested
    @DisplayName("Voyage")
    class Voyage {

        private VoyageEmbeddingAdapter adapter(String baseUrl, int dimensions) {
            VoyageEmbeddingAdapter adapter = new VoyageEmbeddingAdapter();
            adapter.setBaseUrl(baseUrl);
            adapter.setApiKey(VOYAGE_KEY);
            adapter.setModel("voyage-test");
            adapter.setDimensions(dimensions);
            adapter.setTimeout(Duration.ofSeconds(5));
            adapter.requireApiKey();
            return adapter;
        }

        @Test
        @DisplayName("câu hỏi và tài liệu khai input_type khác nhau — khai sai không báo lỗi, chỉ làm tìm kiếm tệ")
        void phan_biet_cau_hoi_va_tai_lieu() throws Exception {
            server.respond(200, "{\"data\":[{\"embedding\":" + vectorOf(4) + "}]}");
            VoyageEmbeddingAdapter adapter = adapter(server.baseUrl(), 4);

            adapter.embedQuery("hoàn vé thế nào");
            adapter.embedDocument("Chính sách hoàn vé");

            JsonNode query = JSON.readTree(server.received().get(0).body());
            JsonNode document = JSON.readTree(server.received().get(1).body());
            assertThat(query.get("input_type").asText()).isEqualTo("query");
            assertThat(query.get("input").asText()).isEqualTo("hoàn vé thế nào");
            assertThat(query.get("model").asText()).isEqualTo("voyage-test");
            assertThat(document.get("input_type").asText()).isEqualTo("document");
        }

        @Test
        @DisplayName("khoá đi bằng header Authorization tới /v1/embeddings, không nằm trong body")
        void khoa_di_bang_header() {
            server.respond(200, "{\"data\":[{\"embedding\":" + vectorOf(4) + "}]}");

            adapter(server.baseUrl(), 4).embedQuery("x");

            StubHttpServer.Received sent = server.only();
            assertThat(sent.path()).isEqualTo("/v1/embeddings");
            assertThat(sent.header("authorization")).isEqualTo("Bearer " + VOYAGE_KEY);
            assertThat(sent.body()).doesNotContain(VOYAGE_KEY);
        }

        @Test
        @DisplayName("vector trả về được đổi sang float[] đúng giá trị và số chiều")
        void doi_sang_float() {
            server.respond(200, "{\"data\":[{\"embedding\":[0.5,0.25,-1.0]}]}");
            VoyageEmbeddingAdapter adapter = adapter(server.baseUrl(), 3);

            float[] vector = adapter.embedDocument("x");

            assertThat(vector).containsExactly(0.5f, 0.25f, -1.0f);
            assertThat(adapter.dimensions()).isEqualTo(3);
        }

        @Test
        @DisplayName("sai số chiều bị chặn tại đây thay vì để Postgres từ chối ghi")
        void sai_so_chieu() {
            server.respond(200, "{\"data\":[{\"embedding\":" + vectorOf(5) + "}]}");

            assertThatThrownBy(() -> adapter(server.baseUrl(), 4).embedQuery("x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("voyage-test")
                    .hasMessageContaining("5 chiều")
                    .hasMessageContaining("khai 4");
        }

        @Test
        @DisplayName("data rỗng hoặc thiếu thì ném IllegalStateException")
        void data_rong() {
            for (String body : new String[] {"{\"data\":[]}", "{}", ""}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(200, body);

                    assertThatThrownBy(() -> adapter(local.baseUrl(), 4).embedQuery("x"))
                            .as("body=[%s]", body)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("rỗng");
                }
            }
        }

        @Test
        @DisplayName("401 và 500 ném lỗi RestClient, không che đi và không lộ khoá trong thông báo")
        void loi_http_khong_lo_khoa() {
            for (int status : new int[] {401, 429, 500}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(status, "{\"detail\":\"loi\"}");

                    Throwable thrown =
                            catchThrowable(() -> adapter(local.baseUrl(), 4).embedQuery("x"));

                    assertThat(thrown).as("HTTP %d", status).isInstanceOf(RuntimeException.class);
                    for (Throwable t = thrown; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(VOYAGE_KEY);
                    }
                    assertThat(local.received()).hasSize(1);
                }
            }
        }

        @Test
        @DisplayName("mạng đứt ném lỗi, không trả vector rỗng")
        void mang_dut() {
            assertThatThrownBy(() -> adapter(StubHttpServer.deadBaseUrl(), 4).embedQuery("x"))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("thiếu khoá thì không khởi động, thông báo nói rõ cần CẢ HAI khoá")
        void thieu_khoa_thi_khong_khoi_dong() {
            VoyageEmbeddingAdapter noKey = new VoyageEmbeddingAdapter();
            VoyageEmbeddingAdapter blankKey = new VoyageEmbeddingAdapter();
            blankKey.setApiKey("   ");

            assertThatThrownBy(noKey::requireApiKey)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("VOYAGE_API_KEY")
                    .hasMessageContaining("Anthropic");
            assertThatThrownBy(blankKey::requireApiKey).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("mặc định: voyage-3.5, 1024 chiều")
        void mac_dinh() {
            assertThat(new VoyageEmbeddingAdapter().dimensions()).isEqualTo(1024);
        }
    }

    @Nested
    @DisplayName("Ollama")
    class OllamaEmbedding {

        private OllamaEmbeddingAdapter adapter(String baseUrl, int dimensions, Duration timeout) {
            return new OllamaEmbeddingAdapter(
                    RestClient.builder(),
                    new OllamaProperties(
                            baseUrl, "chat", "bge-test", dimensions, Duration.ofSeconds(5), timeout, 10, 0, null, 0));
        }

        @Test
        @DisplayName("gọi /api/embed với mô hình nhúng; câu hỏi và tài liệu đi cùng một đường (bge-m3 đối xứng)")
        void goi_api_embed() throws Exception {
            server.respond(200, "{\"embeddings\":[" + vectorOf(4) + "]}");
            OllamaEmbeddingAdapter adapter = adapter(server.baseUrl(), 4, Duration.ofSeconds(5));

            float[] query = adapter.embedQuery("hoàn vé");
            float[] document = adapter.embedDocument("hoàn vé");

            assertThat(server.received()).hasSize(2);
            StubHttpServer.Received sent = server.received().get(0);
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.path()).isEqualTo("/api/embed");
            JsonNode body = JSON.readTree(sent.body());
            assertThat(body.get("model").asText()).isEqualTo("bge-test");
            assertThat(body.get("input").asText()).isEqualTo("hoàn vé");
            assertThat(query).hasSize(4).isEqualTo(document);
            assertThat(adapter.dimensions()).isEqualTo(4);
        }

        @Test
        @DisplayName("vector được đổi sang float[] đúng giá trị")
        void doi_sang_float() {
            server.respond(200, "{\"embeddings\":[[0.5,-0.25,1.0]]}");

            float[] vector = adapter(server.baseUrl(), 3, Duration.ofSeconds(5)).embedQuery("x");

            assertThat(vector).containsExactly(0.5f, -0.25f, 1.0f);
        }

        @Test
        @DisplayName("sai số chiều ném IllegalStateException nêu cả mô hình lẫn hai con số")
        void sai_so_chieu() {
            server.respond(200, "{\"embeddings\":[" + vectorOf(6) + "]}");

            assertThatThrownBy(() ->
                            adapter(server.baseUrl(), 4, Duration.ofSeconds(5)).embedQuery("x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bge-test")
                    .hasMessageContaining("6 chiều")
                    .hasMessageContaining("khai 4");
        }

        @Test
        @DisplayName(
                "không có vector (thiếu embeddings, mảng rỗng, vector rỗng, body rỗng) thì ném, không trả mảng rỗng")
        void khong_co_vector() {
            for (String body :
                    new String[] {"{}", "{\"embeddings\":[]}", "{\"embeddings\":[[]]}", "{\"embeddings\":[1]}", ""}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(200, body);

                    assertThatThrownBy(() -> adapter(local.baseUrl(), 4, Duration.ofSeconds(5))
                                    .embedQuery("x"))
                            .as("body=[%s]", body)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("bge-test");
                }
            }
        }

        @Test
        @DisplayName("404 (chưa pull mô hình) và 500 ném lỗi RestClient")
        void loi_http() {
            for (int status : new int[] {404, 500}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(status, "{\"error\":\"loi\"}");

                    assertThatThrownBy(() -> adapter(local.baseUrl(), 4, Duration.ofSeconds(5))
                                    .embedQuery("x"))
                            .as("HTTP %d", status)
                            .isInstanceOf(RuntimeException.class);
                }
            }
        }

        @Test
        @DisplayName("quá hạn nhúng ném lỗi, hạn lấy từ embeddingTimeout chứ không phải timeout của chat")
        void qua_han() {
            server.hang();

            assertThatThrownBy(() ->
                            adapter(server.baseUrl(), 4, Duration.ofMillis(300)).embedQuery("x"))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("Ollama chưa chạy ném lỗi")
        void chua_chay() {
            assertThatThrownBy(() -> adapter(StubHttpServer.deadBaseUrl(), 4, Duration.ofSeconds(5))
                            .embedQuery("x"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Tắt (EMBEDDING_PROVIDER=none)")
    class Disabled {

        @Test
        @DisplayName("nhúng ném IllegalStateException để use case đi đường 'không có RAG', nhưng vẫn báo số chiều thật")
        void nem_loi_nhung_van_bao_so_chieu() {
            DisabledEmbeddingAdapter adapter = new DisabledEmbeddingAdapter(768);

            assertThatThrownBy(() -> adapter.embedQuery("x")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> adapter.embedDocument("x")).isInstanceOf(IllegalStateException.class);
            assertThat(adapter.dimensions()).isEqualTo(768);
        }
    }
}
