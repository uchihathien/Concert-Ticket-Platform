// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.domain.port;

import com.nexaticket.ordering.domain.model.CheckoutSaga;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutSagaRepository {

    void save(CheckoutSaga saga);

    void update(CheckoutSaga saga);

    Optional<CheckoutSaga> findByOrderId(UUID orderId);

    /**
     * Các saga cần bù trừ lại — lưới an toàn thứ hai (sagas.md §2).
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} để nhiều instance chạy job cùng lúc mà không bù trừ trùng.
     */
    List<CheckoutSaga> claimCompensationPending(Instant notUpdatedSince, int batchSize);
}
