// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.application.AiChatboxErrorCode;
import com.nexaticket.platform.idempotency.IdempotencyStore;
import com.nexaticket.platform.web.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Chống gửi trùng cho lượt chat.
 *
 * <h2>Vì sao endpoint này cần, trong khi phần lớn endpoint khác thì không</h2>
 *
 * <p>Gửi trùng ở hầu hết nơi khác là một dòng thừa trong database. Ở đây nó là <b>một lần gọi mô
 * hình nữa đã tính tiền</b>, cộng một cặp tin nhắn trùng mà khách nhìn thấy. Và kịch bản gây ra nó
 * hoàn toàn bình thường: mạng rớt sau khi server đã ghi xong nhưng trước khi phản hồi về tới nơi —
 * khách thấy lỗi, bấm gửi lại, và câu hỏi ấy được trả lời hai lần bằng hai lần tiền.
 *
 * <h2>Tuỳ chọn, không bắt buộc — khác {@code POST /orders}</h2>
 *
 * <p>Đặt hàng mà gửi trùng là hai đơn hàng: sai dữ liệu, phải bắt buộc. Chat gửi trùng là tốn tiền
 * và hơi khó coi: đủ nghiêm trọng để chống, không đủ để từ chối một client chưa kịp cập nhật. Giao
 * diện của ta luôn gửi khoá; client nào không gửi thì mất phần bảo vệ này chứ không mất dịch vụ.
 *
 * <h2>So khớp cả nội dung, không chỉ khoá</h2>
 *
 * <p>Cùng một khoá với nội dung khác nhau là lỗi của client, không phải một lần gửi lại — và trả về
 * câu trả lời cũ cho một câu hỏi mới là kiểu hỏng tệ nhất có thể: im lặng và sai. Nên khoá được
 * chốt cùng một chữ ký của chính request.
 */
@Component
public class ChatIdempotency {

    private final IdempotencyStore store;
    private final ObjectMapper json;

    public ChatIdempotency(IdempotencyStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    /**
     * Giữ chỗ cho một lượt sắp chạy.
     *
     * @return rỗng nghĩa là <b>ta sở hữu lượt này</b>, cứ chạy; khác rỗng là câu trả lời cũ, trả
     *     thẳng về mà không gọi mô hình
     * @throws ApiException 409 khi khoá bị dùng lại cho một nội dung khác, hoặc 503 khi đúng request
     *     ấy đang chạy dở ở một nơi khác
     */
    public Optional<SupportChatController.AskResponse> beginOrReplay(
            UUID userId, String key, UUID sessionId, String message) {

        String hash = fingerprint(sessionId, message);
        if (store.tryBegin(userId, key, hash)) {
            return Optional.empty();
        }

        IdempotencyStore.Existing prior = store.find(userId, key)
                // Chen giữa hai lệnh: ai đó vừa release đúng lúc. Coi như chưa từng có khoá.
                .orElseThrow(() -> new ApiException(
                        AiChatboxErrorCode.ASSISTANT_BUSY, "Yêu cầu này vừa được xử lý lại, bạn gửi lại giúp mình"));

        if (!prior.requestHash().equals(hash)) {
            throw new ApiException(
                    AiChatboxErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key này đã dùng cho một câu hỏi khác");
        }
        if (!prior.isComplete()) {
            // Lượt trước chưa xong. Trả 503 thay vì chờ: giữ kết nối này lại để đợi một lượt chat
            // khác kết thúc là biến một request thành hai request cùng chiếm chỗ.
            throw new ApiException(AiChatboxErrorCode.ASSISTANT_BUSY, "Câu hỏi này đang được xử lý, chờ chút nhé");
        }
        return Optional.of(read(prior.responseBody()));
    }

    /** Lượt chạy xong — lưu câu trả lời để lần gửi lại nhận đúng nó. */
    public void complete(UUID userId, String key, SupportChatController.AskResponse response) {
        store.complete(userId, key, 200, write(response));
    }

    /**
     * Lượt hỏng — nhả chỗ để khách gửi lại được ngay.
     *
     * <p>Không nhả thì một lần 503 tạm thời khoá cứng đúng câu hỏi ấy: mọi lần thử lại đều rơi vào
     * nhánh "đang xử lý" của một lượt không bao giờ kết thúc.
     */
    public void release(UUID userId, String key) {
        store.release(userId, key);
    }

    /**
     * Chữ ký của request: phiên + nội dung.
     *
     * <p>Băm chứ không lưu thẳng câu hỏi — cột này chỉ để so sánh, và một bản sao nữa của nội dung
     * khách nhắn là một chỗ nữa nó có thể rò.
     */
    private static String fingerprint(UUID sessionId, String message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(sessionId).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không có SHA-256", e);
        }
    }

    private String write(SupportChatController.AskResponse response) {
        try {
            return json.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không tuần tự hoá được câu trả lời", e);
        }
    }

    private SupportChatController.AskResponse read(String body) {
        try {
            return json.readValue(body, SupportChatController.AskResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không đọc lại được câu trả lời đã lưu", e);
        }
    }
}
