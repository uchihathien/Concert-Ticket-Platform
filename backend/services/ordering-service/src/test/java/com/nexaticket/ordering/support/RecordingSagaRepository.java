// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.support;

import com.nexaticket.ordering.domain.model.CheckoutSaga;
import com.nexaticket.ordering.domain.model.SagaStatus;
import com.nexaticket.ordering.domain.port.CheckoutSagaRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Kho saga trong bộ nhớ, ghi lại <b>ảnh chụp</b> trạng thái ở từng lần ghi.
 *
 * <p>Saga là đối tượng khả biến: mock thường chỉ thấy trạng thái cuối cùng. Thứ cần khẳng định ở
 * đây là cờ đã được ghi xuống TRƯỚC lời gọi mạng — nên phải chụp lại ngay lúc ghi.
 */
public class RecordingSagaRepository implements CheckoutSagaRepository {

    /** Ảnh chụp một lần ghi. */
    public record Snapshot(String op, SagaStatus status, boolean seatsReserved, boolean intentOpen, int attempts) {}

    public final List<Snapshot> writes = new ArrayList<>();
    public final List<CheckoutSaga> pending = new ArrayList<>();
    public Instant lastNotUpdatedSince;
    public int lastBatchSize;

    @Override
    public void save(CheckoutSaga saga) {
        writes.add(snapshot("save", saga));
    }

    @Override
    public void update(CheckoutSaga saga) {
        writes.add(snapshot("update", saga));
    }

    @Override
    public Optional<CheckoutSaga> findByOrderId(UUID orderId) {
        return Optional.empty();
    }

    @Override
    public List<CheckoutSaga> claimCompensationPending(Instant notUpdatedSince, int batchSize) {
        this.lastNotUpdatedSince = notUpdatedSince;
        this.lastBatchSize = batchSize;
        return List.copyOf(pending);
    }

    public Snapshot last() {
        return writes.get(writes.size() - 1);
    }

    private static Snapshot snapshot(String op, CheckoutSaga saga) {
        return new Snapshot(op, saga.status(), saga.isSeatsReserved(), saga.isPaymentIntentOpen(), saga.attempts());
    }
}
