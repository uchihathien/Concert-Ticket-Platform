// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Chốt chống xử lý trùng message.
 *
 * <p>Dùng bên trong transaction của consumer:
 *
 * <pre>{@code
 * if (!processedEvents.markIfNew(queue, eventId)) return;   // đã xử lý rồi, ack và bỏ qua
 * ... xử lý nghiệp vụ ...
 * }</pre>
 *
 * <p>Bảng này cũng khiến việc replay từ outbox trở nên an toàn: xoá bản ghi của một consumer rồi
 * replay sẽ dựng lại trạng thái mà không nhân đôi tác dụng.
 */
public class ProcessedEvents {

    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true nếu đây là lần đầu thấy event này
     */
    public boolean markIfNew(String consumerQueue, String eventId) {
        int inserted = jdbc.update(
                """
                INSERT INTO processed_events (consumer_queue, event_id)
                VALUES (?, ?)
                ON CONFLICT (consumer_queue, event_id) DO NOTHING
                """,
                consumerQueue,
                eventId);
        return inserted == 1;
    }
}
