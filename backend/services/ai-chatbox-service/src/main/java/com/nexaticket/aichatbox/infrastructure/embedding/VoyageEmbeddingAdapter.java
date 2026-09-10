// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.embedding;

import com.nexaticket.aichatbox.domain.port.EmbeddingPort;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Nhúng văn bản bằng Voyage AI.
 *
 * <p><b>Vì sao không phải Anthropic.</b> Claude không có endpoint nhúng — nó sinh văn bản, không
 * sinh vector. Voyage là nhà cung cấp Anthropic khuyến nghị đi kèm; đổi sang nhà cung cấp khác thì
 * thay lớp này, phần còn lại của agent không biết.
 *
 * <p><b>Số chiều là hợp đồng với database.</b> {@code voyage-3.5} sinh vector 1024 chiều, và cột
 * {@code vector(1024)} trong migration phải khớp. Đổi mô hình mà không đổi cột thì Postgres từ
 * chối ghi — đó là trường hợp may. Trường hợp xui là đổi sang mô hình <i>cũng</i> 1024 chiều: mọi
 * thứ chạy trơn tru, truy vấn vẫn trả kết quả, và kết quả vô nghĩa vì vector cũ và mới không nằm
 * trong cùng một không gian. Đổi mô hình ⇒ nhúng lại toàn bộ kho tri thức.
 */
@Component
@ConfigurationProperties(prefix = "nexaticket.aichatbox.embedding")
public class VoyageEmbeddingAdapter implements EmbeddingPort {

    private String baseUrl = "https://api.voyageai.com";
    private String apiKey;
    private String model = "voyage-3.5";
    private int dimensions = 1024;
    private Duration timeout = Duration.ofSeconds(10);

    private RestClient client;

    @Override
    public float[] embedQuery(String text) {
        return embed(text, "query");
    }

    @Override
    public float[] embedDocument(String text) {
        return embed(text, "document");
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    /**
     * @param inputType "query" hay "document" — nhà cung cấp nhúng hai loại khác nhau, và khai sai
     *     không gây lỗi nào, chỉ làm kết quả tìm kiếm tệ đi một cách khó truy nguyên
     */
    private float[] embed(String text, String inputType) {
        VoyageResponse response = client().post()
                .uri("/v1/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of("model", model, "input", text, "input_type", inputType))
                .retrieve()
                .body(VoyageResponse.class);

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException("Voyage trả về rỗng");
        }
        List<Double> values = response.data().get(0).embedding();
        if (values.size() != dimensions) {
            // Chặn ngay tại đây thay vì để Postgres từ chối ghi: thông báo ở đó nói về kiểu cột,
            // không nói rằng cấu hình mô hình và migration đã lệch nhau.
            throw new IllegalStateException(
                    "Mô hình %s trả %d chiều, cấu hình khai %d".formatted(model, values.size(), dimensions));
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }

    private RestClient client() {
        if (client == null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(timeout);
            factory.setReadTimeout(timeout);
            client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(factory)
                    .build();
        }
        return client;
    }

    private record VoyageResponse(List<Item> data) {
        private record Item(List<Double> embedding) {}
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
