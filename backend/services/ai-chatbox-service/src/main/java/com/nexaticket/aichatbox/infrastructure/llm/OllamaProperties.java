// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tham số của Ollama.
 *
 * @param chatModel mô hình trả lời. Mặc định {@code qwen2.5:7b-instruct} vì hai điều kiện phải
 *     thoả cùng lúc: nó hỗ trợ tool calling (phần lớn mô hình nhỏ thì không), và nó viết tiếng
 *     Việt đọc được. Bỏ một trong hai thì agent này không chạy — nó sống bằng tool, và khách là
 *     người Việt.
 * @param embeddingModel mô hình nhúng. Mặc định {@code bge-m3} vì nó sinh vector <b>1024 chiều</b>,
 *     đúng bằng {@code vector(1024)} trong V0100 — cột ấy được khai cho voyage-3.5, và trùng số
 *     chiều là điều kiện để đổi nhà cung cấp mà không phải sửa schema. Đổi sang mô hình 768 chiều
 *     (ví dụ nomic-embed-text) thì phải đổi cột VÀ nhúng lại toàn bộ kho tri thức; xem ghi chú ở
 *     {@code EmbeddingPort}.
 * @param timeout rộng hơn hẳn hạn của API trả phí. Mô hình local chạy trên CPU thì một lượt 30–60
 *     giây là bình thường, và cắt ở 60s như cấu hình Anthropic sẽ biến máy chậm thành "trợ lý luôn
 *     hỏng".
 * @param embeddingTimeout hạn RIÊNG cho nhúng, và nó tồn tại vì một con số đo được: khi mô hình
 *     chưa nằm trong bộ nhớ, lời gọi nhúng đầu tiên mất <b>21 giây</b> trên máy phát triển (những
 *     lời gọi sau: 130 mili-giây). Hạn 30s từng được dùng cho cả hai và nó đã hỏng thật — lần khởi
 *     động đầu tiên sau khi pull mô hình, Ollama vừa nạp mô hình vừa tranh I/O, và toàn bộ việc nạp
 *     tri thức nền chết ở {@code CancellationException} sau đúng 30 giây. Không gộp vào
 *     {@code timeout} của chat: 120 giây cho một lời gọi nhúng chỉ làm người soạn kho tri thức chờ
 *     hai phút để biết Ollama không chạy.
 * @param numCtx cửa sổ ngữ cảnh gửi kèm mỗi lời gọi ({@code options.num_ctx}). Không khai thì
 *     Ollama dùng mặc định 2048–4096 token và <b>lặng lẽ bỏ message cũ nhất</b> khi vượt — prompt hệ
 *     thống tiếng Việt + 9 schema tool + 10 lượt + 5 đoạn RAG + kết quả tool dễ vượt mức đó, và từ
 *     vòng thứ hai thứ bị bỏ có thể chính là câu hỏi của khách. 8192 là thoả hiệp: lớn hơn thì máy 4 GB
 *     VRAM chậm thêm.
 * @param keepAlive gửi kèm mỗi lời gọi ({@code keep_alive}): Ollama giữ mô hình trong bộ nhớ bấy nhiêu
 *     lâu sau lời gọi cuối. Mặc định của Ollama là 5 phút — câu hỏi đầu tiên sau một khoảng lặng
 *     ngắn phải trả thêm 10–60 giây nạp lại 4,7 GB. 30 phút là đủ cho một ca hỗ trợ có lúc vắng khách.
 * @param embeddingGpuLayers số lớp của mô hình NHÚNG đưa lên GPU ({@code options.num_gpu}); {@code 0}
 *     là chạy hẳn trên CPU, {@code -1} là để Ollama tự quyết. Mặc định 0, vì một con số đo được trên
 *     máy 4 GB VRAM: mô hình chat (7B, 2,3 GB trên GPU) và bge-m3 (0,7 GB) KHÔNG cùng vừa, nên mỗi
 *     lượt chat Ollama dỡ cái này để nạp cái kia — nhúng câu hỏi rồi nạp lại mô hình chat, 10–60 giây
 *     mỗi lượt, chiếm phần lớn 80–110 giây một câu trả lời. Nhúng một câu hỏi trên CPU mất vài trăm
 *     mili-giây; để mô hình chat nằm yên trên GPU đáng giá hơn nhiều.
 */
@ConfigurationProperties(prefix = "nexaticket.aichatbox.ollama")
public record OllamaProperties(
        String baseUrl,
        String chatModel,
        String embeddingModel,
        int dimensions,
        Duration timeout,
        Duration embeddingTimeout,
        int maxOutputTokens,
        int numCtx,
        Duration keepAlive,
        int embeddingGpuLayers) {

    public OllamaProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:11434";
        }
        if (chatModel == null || chatModel.isBlank()) {
            chatModel = "qwen2.5:7b-instruct";
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            embeddingModel = "bge-m3";
        }
        if (dimensions <= 0) {
            dimensions = 1024;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(120);
        }
        if (embeddingTimeout == null || embeddingTimeout.isZero() || embeddingTimeout.isNegative()) {
            embeddingTimeout = Duration.ofSeconds(60);
        }
        if (maxOutputTokens <= 0) {
            maxOutputTokens = 320;
        }
        if (numCtx <= 0) {
            numCtx = 8192;
        }
        if (keepAlive == null || keepAlive.isZero() || keepAlive.isNegative()) {
            keepAlive = Duration.ofMinutes(30);
        }
        if (embeddingGpuLayers < -1) {
            embeddingGpuLayers = -1;
        }
    }

    /** Dạng Ollama hiểu: số giây, hoặc chuỗi kiểu Go ("30m"). Dùng số để không phụ thuộc định dạng. */
    public long keepAliveSeconds() {
        return keepAlive.toSeconds();
    }
}
