// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.core.Message;

/**
 * Một message đã bóc tách, sẵn sàng cho consumer.
 *
 * <p>Tồn tại để bốn service không mỗi nơi tự bóc header một kiểu. Việc đọc header nghe đơn giản
 * nhưng có đúng một chỗ dễ sai âm thầm: <b>khoá chống trùng phải là {@code messageId}</b>, tức id
 * dòng outbox, chứ không phải {@code aggregateId}. Một aggregate phát nhiều sự kiện — dùng
 * {@code aggregateId} làm khoá thì sự kiện thứ hai của cùng một đơn hàng bị coi là trùng và bị bỏ
 * lặng lẽ.
 *
 * @param eventId id dòng outbox — khoá chống trùng, duy nhất cho mỗi lần phát
 * @param eventType cũng là routing key, ví dụ {@code order.paid}
 * @param aggregateId id của aggregate phát ra sự kiện
 * @param payload thân message đã parse
 */
public record ConsumedEvent(UUID eventId, String eventType, UUID aggregateId, JsonNode payload) {

    /**
     * @throws MalformedEventException khi thiếu header bắt buộc hoặc thân message không parse được
     */
    public static ConsumedEvent from(Message message, ObjectMapper json) {
        var headers = message.getMessageProperties();
        String messageId = headers.getMessageId();
        if (messageId == null) {
            throw new MalformedEventException("Message không có messageId nên không chống trùng được");
        }
        // Gán ra biến Object TRƯỚC khi gọi String.valueOf.
        //
        // MessageProperties.getHeader khai báo là <T> T getHeader(String), tức generic không
        // ràng buộc. Truyền thẳng vào String.valueOf(...) thì compiler suy ra T = char[] và
        // chọn overload valueOf(char[]) — code biên dịch sạch, rồi nổ lúc chạy với
        // "class java.lang.String cannot be cast to class [C". Biến trung gian kiểu Object ép
        // chọn đúng overload valueOf(Object).
        Object eventType = headers.getHeader("eventType");
        Object aggregateId = headers.getHeader("aggregateId");
        try {
            return new ConsumedEvent(
                    UUID.fromString(messageId),
                    eventType == null ? null : eventType.toString(),
                    uuidHeader(aggregateId),
                    json.readTree(new String(message.getBody(), StandardCharsets.UTF_8)));
        } catch (MalformedEventException e) {
            throw e;
        } catch (Exception e) {
            throw new MalformedEventException("Không đọc được message: " + e.getMessage());
        }
    }

    private static UUID uuidHeader(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    /** Đọc một trường chuỗi, trả {@code null} nếu không có — payload có thể thêm/bớt trường theo thời gian. */
    public String text(String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    public UUID uuid(String field) {
        String value = text(field);
        return value == null ? null : UUID.fromString(value);
    }

    public long number(String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? 0L : node.asLong();
    }

    /**
     * Message hỏng vĩnh viễn.
     *
     * <p>Consumer bắt lỗi này và <b>ack</b> rồi ghi log, không nack: nack sẽ khiến broker giao lại
     * mãi một message không bao giờ xử lý được, và nó chặn cả queue phía sau.
     */
    public static class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message) {
            super(message);
        }
    }
}
