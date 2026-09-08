// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.port;

import java.util.UUID;

/**
 * Báo cho realtime-gateway rằng tồn kho của một suất đã đổi.
 *
 * <p>Sự kiện này <b>không đi qua outbox</b>: dữ liệu tạm, tần suất cao, mất được. Client phát hiện
 * nhảy version sẽ tự refetch, nên một message rơi không gây hậu quả — trong khi ghi mọi thay đổi
 * ghế vào outbox sẽ làm bảng outbox phình lên vô ích (services.md §3).
 */
public interface AvailabilityPublisher {

    /** Gọi <b>sau commit</b>, không phải trước — nếu không, client refetch trúng dữ liệu cũ. */
    void availabilityChanged(UUID eventSessionId, long version);
}
