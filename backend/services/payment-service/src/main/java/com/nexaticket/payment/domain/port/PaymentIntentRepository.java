// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.port;

import com.nexaticket.payment.domain.model.PaymentIntent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository {

    void save(PaymentIntent intent);

    Optional<PaymentIntent> findByOrderId(UUID orderId);

    /**
     * Tra intent theo mã tham chiếu và <b>khoá dòng</b>.
     *
     * <p>{@code SELECT ... FOR UPDATE} là bắt buộc: webhook có thể đến đúng lúc worker hết hạn
     * đang chạy trên cùng đơn đó (BRAINSTORM §4.4). Không khoá thì hai bên cùng đọc trạng thái
     * PENDING, một bên ghi CONFIRMED, bên kia ghi EXPIRED, và kết quả tuỳ ai commit sau.
     */
    Optional<PaymentIntent> lockByReference(String paymentReference);

    void updateStatus(PaymentIntent intent);

    List<PaymentIntent> claimExpired(Instant now, int batchSize);
}
