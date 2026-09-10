// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.infrastructure.amqp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.kernel.money.Money;
import com.nexaticket.ledger.application.command.RecordPaymentHandler;
import com.nexaticket.platform.idempotency.ConsumedEvent;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nhận {@code order.paid} và ghi bút toán N1 — tiền khách vào tài khoản ký quỹ.
 *
 * <p>Trước consumer này, {@link RecordPaymentHandler} <b>chỉ được test gọi</b>: tiền thật vào, vé
 * thật ra, và sổ cái trống rỗng. Hệ quả không dừng ở kế toán — payout-service tính số phải trả cho
 * ban tổ chức từ chính số dư này, nên một sổ cái trống nghĩa là không ai được chi trả.
 *
 * <h2>Vì sao nghe {@code order.paid} chứ không phải {@code payment.confirmed}</h2>
 *
 * <p>ADR-1009 vẽ đường {@code payment.confirmed} từ payment-service. Thực tế của code này khác:
 * payment-service báo sang Ordering bằng HTTP đồng bộ và không phát sự kiện nào, còn Ordering thì
 * đã gửi kèm {@code commissionBps}, {@code commissionVnd} và {@code paidAt} trong
 * {@code order.paid} — đúng ba số bút toán N1 cần. Nghe ở đây là dùng thứ đang có thay vì dựng một
 * đường phát sự kiện thứ hai cho cùng một sự thật.
 *
 * <p><b>Chạy tuần tự</b> ({@code concurrency = 1}, ADR-1009 §4). Sổ cái kép có ràng buộc cân bằng ở
 * mức transaction; nhiều consumer song song trên cùng một tổ chức chỉ tạo thêm tranh chấp khoá mà
 * không nhanh hơn.
 *
 * <h2>Chống ghi trùng</h2>
 *
 * <p>Không dùng {@code ProcessedEvents} mà dựa vào {@code idempotencyKey = "order:<id>"} của chính
 * bút toán. Khoá đó mạnh hơn: nó chặn cả khi cùng một đơn tới từ hai message khác nhau (outbox gửi
 * lại sau khi mất xác nhận từ broker), còn khoá theo {@code messageId} thì không.
 */
@Component
public class OrderPaidListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidListener.class);
    private static final String QUEUE = "ledger.ordering.paid";

    private final RecordPaymentHandler recordPayment;
    private final ObjectMapper json;

    public OrderPaidListener(RecordPaymentHandler recordPayment, ObjectMapper json) {
        this.recordPayment = recordPayment;
        this.json = json;
    }

    @RabbitListener(queues = QUEUE, concurrency = "1")
    @Transactional
    public void onOrderPaid(Message message) {
        ConsumedEvent event;
        try {
            event = ConsumedEvent.from(message, json);
        } catch (ConsumedEvent.MalformedEventException e) {
            log.error("Bỏ qua message order.paid hỏng: {}", e.getMessage());
            return;
        }

        UUID orderId = event.uuid("orderId");
        UUID organizationId = event.uuid("organizationId");
        Instant paidAt = instantOf(event.text("paidAt"));
        if (orderId == null || organizationId == null || paidAt == null) {
            // Không đoán một mốc thời gian hay một tổ chức: bút toán sai còn khó gỡ hơn bút toán
            // thiếu. Ack rồi log ERROR — giao lại cũng không làm trường thiếu xuất hiện, mà một
            // message không bao giờ xử lý được sẽ chặn đầu hàng đợi tuần tự này.
            log.error("Message order.paid thiếu orderId/organizationId/paidAt hợp lệ, bỏ qua ghi sổ");
            return;
        }

        Money gross = Money.ofVnd(event.number("totalVnd"));
        Money commission = Money.ofVnd(event.number("commissionVnd"));
        int commissionBps = (int) event.number("commissionBps");

        UUID entryId = recordPayment.handle(RecordPaymentHandler.Command.ofOrder(
                orderId, organizationId, gross, commission, commissionBps, paidAt));

        log.info("Đã ghi bút toán {} cho đơn {} ({}đ)", entryId, orderId, gross.amountVnd());
    }

    /** Mốc thời gian hỏng là message hỏng, không phải lý do để giao lại mãi. */
    private static Instant instantOf(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
