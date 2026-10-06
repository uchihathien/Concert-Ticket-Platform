// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.infrastructure.persistence;

import com.nexaticket.payout.domain.port.ReconciliationRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReconciliationRepository implements ReconciliationRepository {

    private final JdbcTemplate jdbc;

    public JdbcReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isClosed(LocalDate date) {
        // Không có bản ghi cũng tính là CHƯA đóng: một hệ thống chưa từng chạy đối soát lần
        // nào thì càng không nên chi tiền.
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_days WHERE business_date = ? AND status = 'CLOSED'",
                Integer.class,
                Date.valueOf(date));
        return count != null && count > 0;
    }

    @Override
    public void close(LocalDate date, long expectedVnd, long actualVnd, UUID closedBy) {
        long variance = actualVnd - expectedVnd;
        jdbc.update(
                """
                INSERT INTO reconciliation_days (business_date, status, expected_vnd, actual_vnd,
                                                 variance_vnd, closed_at, closed_by)
                VALUES (?, ?, ?, ?, ?, now(), ?)
                ON CONFLICT (business_date) DO UPDATE
                   SET status = EXCLUDED.status, expected_vnd = EXCLUDED.expected_vnd,
                       actual_vnd = EXCLUDED.actual_vnd, variance_vnd = EXCLUDED.variance_vnd,
                       closed_at = now(), closed_by = EXCLUDED.closed_by
                """,
                Date.valueOf(date),
                // Lệch tiền thì KHÔNG đóng: đóng một ngày có chênh lệch là chôn vấn đề lại,
                // và cổng chặn chi trả sẽ cho qua trên một con số đã biết là sai.
                variance == 0 ? "CLOSED" : "DISCREPANCY",
                expectedVnd,
                actualVnd,
                variance,
                closedBy);
    }
}
