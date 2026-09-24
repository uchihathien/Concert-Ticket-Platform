// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.nexaticket.aichatbox.domain.model.ToolInvocation;
import com.nexaticket.aichatbox.domain.port.EmbeddingPort;
import com.nexaticket.aichatbox.domain.port.LlmProviderPort;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Mô hình và bộ nhúng giả — điều khiển được từng lượt.
 *
 * <p>Không dùng mocking framework, theo đúng cách các service khác làm: những gì cần ở đây là "lượt
 * sau trả về cái này" và "đã gọi mấy lần", và một lớp thường nói ra hai điều đó rõ hơn một chuỗi
 * {@code when().thenReturn()} rải khắp test.
 */
@TestConfiguration
public class FakeAiProviders {

    @Bean
    @Primary
    public FakeLlm fakeLlm() {
        return new FakeLlm();
    }

    @Bean
    @Primary
    public FakeEmbeddings fakeEmbeddings() {
        return new FakeEmbeddings();
    }

    /** Mô hình giả: trả lần lượt những lượt đã xếp sẵn, hết thì trả câu mặc định. */
    public static class FakeLlm implements LlmProviderPort {

        public final AtomicInteger calls = new AtomicInteger();
        private final Deque<LlmTurn> scripted = new ArrayDeque<>();
        private LlmTurn fallback = new LlmTurn.Answer("Mình đã tra xong và trả lời bạn đây.");

        /** Xếp một lượt trả lời bằng lời. */
        public FakeLlm willAnswer(String text) {
            scripted.add(new LlmTurn.Answer(text));
            return this;
        }

        /** Xếp một lượt gọi tool. */
        public FakeLlm willCallTool(String toolName, Map<String, Object> arguments) {
            scripted.add(new LlmTurn.ToolRequest(
                    List.of(new ToolInvocation(UUID.randomUUID().toString(), toolName, arguments)),
                    List.of("khối nguyên bản giả")));
            return this;
        }

        /**
         * Mọi lượt đều gọi tool — dùng để ép agent chạm trần vòng ReAct.
         *
         * <p>Đó là đường {@code LOW_CONFIDENCE} và nó rất dễ xảy ra thật với mô hình local 7B trên
         * một kho tri thức rỗng, nên nó phải có test.
         */
        public FakeLlm alwaysCallsTool(String toolName, Map<String, Object> arguments) {
            fallback = new LlmTurn.ToolRequest(
                    List.of(new ToolInvocation(UUID.randomUUID().toString(), toolName, arguments)),
                    List.of("khối nguyên bản giả"));
            return this;
        }

        public void reset() {
            scripted.clear();
            calls.set(0);
            fallback = new LlmTurn.Answer("Mình đã tra xong và trả lời bạn đây.");
        }

        @Override
        public LlmTurn complete(LlmRequest request) {
            calls.incrementAndGet();
            LlmTurn next = scripted.poll();
            return next != null ? next : fallback;
        }
    }

    /**
     * Bộ nhúng giả: túi từ trên 1024 chiều.
     *
     * <p><b>Vì sao không phải vector ngẫu nhiên.</b> Cột là {@code vector(1024)} và truy vấn xếp theo
     * khoảng cách cosine, nên một vector ngẫu nhiên vẫn chạy được lệnh SQL — nhưng thứ tự trả về là
     * vô nghĩa, và một test "tìm được đoạn đúng" như thế chỉ kiểm rằng SQL không ném ngoại lệ. Túi từ
     * cho <b>tương đồng theo từ</b>: hai đoạn dùng chung từ thì gần nhau, khác từ thì xa nhau. Đủ để
     * kiểm thứ tự xếp hạng và ngưỡng cắt, mà vẫn xác định hoàn toàn giữa các lần chạy.
     */
    public static class FakeEmbeddings implements EmbeddingPort {

        private static final int DIMENSIONS = 1024;

        public final AtomicInteger documentCalls = new AtomicInteger();

        /** Bật để mọi lời gọi ném ngoại lệ — mô phỏng Ollama chưa chạy. */
        public boolean broken;

        @Override
        public float[] embedQuery(String text) {
            return embed(text);
        }

        @Override
        public float[] embedDocument(String text) {
            documentCalls.incrementAndGet();
            return embed(text);
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        private float[] embed(String text) {
            if (broken) {
                throw new IllegalStateException("Bộ nhúng giả đang được đặt ở trạng thái hỏng");
            }
            float[] vector = new float[DIMENSIONS];
            for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (!token.isBlank()) {
                    vector[Math.abs(token.hashCode()) % DIMENSIONS] += 1f;
                }
            }
            // pgvector từ chối vector toàn số 0 cho khoảng cách cosine (chia cho chuẩn bằng 0). Một
            // chiều nền nhỏ giữ cho chuỗi rỗng vẫn lưu và tìm được, thay vì hỏng bằng một lỗi nói về
            // phép chia.
            vector[0] += 0.001f;
            return vector;
        }
    }
}
