// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.domain.port;

import java.time.LocalDate;

/** Đối soát ngân hàng theo ngày — cổng chặn số 3 trước khi chi trả. */
public interface ReconciliationRepository {

    /**
     * Ngày làm việc {@code date} đã đóng đối soát chưa.
     *
     * <p>Chưa có bản ghi nào cũng tính là <b>chưa đóng</b>. Mặc định phải là "không cho chi trả":
     * một hệ thống chưa từng chạy đối soát lần nào thì càng không nên chi tiền.
     */
    boolean isClosed(LocalDate date);

    void close(LocalDate date, long expectedVnd, long actualVnd, java.util.UUID closedBy);
}
