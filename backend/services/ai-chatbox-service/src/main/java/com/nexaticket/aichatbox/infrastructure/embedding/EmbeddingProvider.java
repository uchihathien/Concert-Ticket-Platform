// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.embedding;

/**
 * Nhà cung cấp vector nhúng — <b>tách khỏi</b> nhà cung cấp mô hình trả lời.
 *
 * <h2>Vì sao phải tách</h2>
 *
 * <p>Trước đây hai adapter nhúng gắn thẳng vào {@code nexaticket.aichatbox.provider}: Ollama khi
 * {@code local}, Voyage khi {@code anthropic}. Điều đó biến một lựa chọn thành một gói bắt buộc —
 * muốn Claude trả lời thì <b>phải</b> có thêm khoá Voyage, vì {@code VoyageEmbeddingAdapter} kiểm
 * khoá trong {@code @PostConstruct} và service không khởi động nổi khi thiếu.
 *
 * <p>Nhưng hai việc ấy không hề dính nhau. Claude không có endpoint nhúng, nên "nhúng" luôn là một
 * nhà cung cấp riêng; và nhúng là phần RẺ và NHẸ của hệ: {@code bge-m3} chạy trên CPU hết khoảng
 * 1,2 GB và trả vector trong vài trăm mili giây, trong khi mô hình 7B trả lời bằng chữ mất 1,5
 * token/giây (xem {@code LlmProvider}). Buộc chúng vào nhau nghĩa là ai có khoá Anthropic nhưng
 * không có khoá Voyage thì không dùng được Claude — dù phần nhúng hoàn toàn chạy được tại chỗ.
 *
 * <h2>Ba giá trị</h2>
 *
 * <ul>
 *   <li>{@code local} — Ollama với {@code bge-m3}. Không tốn phí, cần container ollama chạy.
 *   <li>{@code anthropic} — Voyage, nhà cung cấp Anthropic khuyến nghị đi kèm. Cần {@code VOYAGE_API_KEY}.
 *   <li>{@code none} — TẮT nhúng. Trợ lý vẫn trả lời và vẫn tra được đơn hàng, nhưng KHÔNG có tri
 *       thức nền: câu hỏi chính sách sẽ chỉ được trả lời từ prompt hệ thống. Có chủ đích là lựa chọn
 *       cuối, không phải mặc định.
 * </ul>
 *
 * <p>Mặc định là chính giá trị của {@code AI_PROVIDER} (xem {@code application.yml}), nên mọi cấu
 * hình đang chạy giữ nguyên hành vi: {@code local} vẫn Ollama, {@code anthropic} vẫn Voyage.
 */
public enum EmbeddingProvider {
    /** Ollama chạy trong chính cụm. */
    LOCAL,
    /** Voyage AI. */
    ANTHROPIC,
    /** Không nhúng — trợ lý mất tri thức nền. */
    NONE;

    /** Khoá cấu hình. Dùng ở {@code @ConditionalOnProperty} nên phải là hằng số biên dịch. */
    public static final String PROPERTY = "nexaticket.aichatbox.embedding.provider";
}
