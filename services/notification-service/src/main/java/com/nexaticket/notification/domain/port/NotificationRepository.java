// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.domain.port;

import com.nexaticket.notification.domain.model.Notification;
import java.time.Instant;
import java.util.List;

public interface NotificationRepository {

    /**
     * Xếp hàng một thư, bỏ qua nếu sự kiện này đã được xử lý.
     *
     * <p>Cài bằng {@code ON CONFLICT (event_id) DO NOTHING}. RabbitMQ giao <b>ít nhất một lần</b>,
     * nên message trùng không phải trường hợp hiếm mà là chuyện thường xuyên: broker khởi động
     * lại, consumer chết trước khi ack, hoặc chính publisher retry.
     *
     * @return true nếu đây là lần đầu thấy sự kiện này
     */
    boolean queueIfNew(Notification notification);

    /** Các thư đến hạn gửi hoặc gửi lại. */
    List<Notification> claimDue(Instant now, int batchSize);

    void update(Notification notification);

    int countByStatus(com.nexaticket.notification.domain.model.NotificationStatus status);
}
