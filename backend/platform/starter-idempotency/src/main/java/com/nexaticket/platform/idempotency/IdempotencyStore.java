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

    /**
     * Nhận lại bản ghi "đang xử lý" đã bỏ dở.
     *
     * <p>Tiến trình đã chết giữa {@link #tryBegin} và {@link #complete} để lại một bản ghi không bao
     * giờ hoàn tất; không nhận lại thì khoá đó trả 409 mãi mãi. Chỉ nhận lại bản ghi CHƯA có câu trả
     * lời và đã cũ hơn {@code staleAfter}.
     *
     * @return true nếu giành được quyền xử lý lại
     */
    public boolean takeOverIfStale(UUID userId, String key, String requestHash, java.time.Duration staleAfter) {
        return jdbc.update(
                        """
                        UPDATE idempotency_records
                           SET request_hash = ?, created_at = now()
                         WHERE user_id IS NOT DISTINCT FROM ? AND idem_key = ?
                           AND response_status IS NULL
                           AND created_at < now() - (? * interval '1 second')
                        """,
                        requestHash,
                        userId,
                        key,
                        staleAfter.toSeconds())
                == 1;
    }

    /**
     * Xoá bản ghi cũ hơn {@code retention}, tối đa {@code batch} dòng một lần.
     *
     * <p>Không phân biệt đã hoàn tất hay đang dở: bản ghi dở quá {@code retention} (hàng chục giờ) chắc chắn là của
     * một tiến trình đã chết và đã được {@link #takeOverIfStale} bỏ qua từ lâu. Chia lô bằng {@code LIMIT} để một
     * lần dọn bảng lớn không giữ khoá dòng lâu; chỉ số {@code idx_idempotency_cleanup} phục vụ đúng câu này.
     *
     * @return số dòng đã xoá; nhỏ hơn {@code batch} nghĩa là đã hết việc
     */
    public int purgeOlderThan(java.time.Duration retention, int batch) {
        return jdbc.update(
                """
                DELETE FROM idempotency_records
                 WHERE id IN (
                       SELECT id FROM idempotency_records
                        WHERE created_at < now() - (? * interval '1 second')
                        ORDER BY created_at
                        LIMIT ?)
                """,
                retention.toSeconds(),
                batch);
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
