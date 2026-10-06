// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Lưu và tra cứu bản ghi idempotency cho mutation HTTP. */
public class IdempotencyStore {

    public record Existing(String requestHash, Integer responseStatus, String responseBody) {
        public boolean isComplete() {
            return responseStatus != null;
        }
    }

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true nếu giành được quyền xử lý; false nghĩa là đã có bản ghi (replay hoặc đang chạy)
     */
    public boolean tryBegin(UUID userId, String key, String requestHash) {
        return jdbc.update(
                        """
                        INSERT INTO idempotency_records (id, user_id, idem_key, request_hash)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (user_id, idem_key) DO NOTHING
                        """,
                        UUID.randomUUID(),
                        userId,
                        key,
                        requestHash)
                == 1;
    }

    public Optional<Existing> find(UUID userId, String key) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT request_hash, response_status, response_body::text
                      FROM idempotency_records
                     WHERE user_id IS NOT DISTINCT FROM ? AND idem_key = ?
                    """,
                    (rs, i) -> new Existing(
                            rs.getString("request_hash"),
                            (Integer) rs.getObject("response_status"),
                            rs.getString("response_body")),
                    userId,
                    key));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void complete(UUID userId, String key, int status, String body) {
        jdbc.update(
                """
                UPDATE idempotency_records
                   SET response_status = ?, response_body = ?::jsonb, completed_at = now()
                 WHERE user_id IS NOT DISTINCT FROM ? AND idem_key = ?
                """,
                status,
                body,
                userId,
                key);
    }

    /** Bỏ chỗ đã giữ khi handler ném lỗi, để client retry được ngay. */
    public void release(UUID userId, String key) {
        jdbc.update(
                """
                DELETE FROM idempotency_records
                 WHERE user_id IS NOT DISTINCT FROM ? AND idem_key = ? AND response_status IS NULL
                """,
                userId,
                key);
    }
}
