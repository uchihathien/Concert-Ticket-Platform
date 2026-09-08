// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Gom các thay đổi trong một cửa sổ thời gian thành <b>một</b> thông báo mỗi suất diễn.
 *
 * <p>Trong một đợt mở bán, một suất diễn hot có thể nhận hàng trăm thay đổi mỗi giây. Đẩy thẳng
 * từng cái xuống 10.000 kết nối là hàng triệu message mỗi giây — client không kịp xử lý và trình
 * duyệt đơ, trong khi thông tin hữu ích chỉ là "có gì đó đổi rồi".
 *
 * <p>Chỉ giữ <b>version lớn nhất</b> của mỗi suất: các version cũ hơn không mang thêm thông tin
 * gì, vì client dùng version chỉ để phát hiện mình đã lạc hậu.
 */
public class UpdateCoalescer {

    private final ConcurrentMap<UUID, Long> pending = new ConcurrentHashMap<>();

    /** Nhận một thay đổi. Nhiều lần gọi cho cùng một suất chỉ tạo một thông báo khi flush. */
    public void accept(AvailabilityUpdate update) {
        pending.merge(update.eventSessionId(), update.version(), Math::max);
    }

    /**
     * Lấy và xoá toàn bộ thay đổi đang chờ.
     *
     * <p>Được gọi định kỳ (mặc định 200ms). Trả về danh sách chứ không tự đẩy, để lớp này không
     * phụ thuộc gì vào WebSocket và kiểm được bằng test thuần.
     */
    public List<AvailabilityUpdate> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<AvailabilityUpdate> drained = new ArrayList<>(pending.size());
        // Duyệt qua keySet rồi remove từng key: gom được cả những cập nhật đến trong lúc đang
        // duyệt vào lượt sau, thay vì mất chúng.
        for (UUID eventSessionId : List.copyOf(pending.keySet())) {
            Long version = pending.remove(eventSessionId);
            if (version != null) {
                drained.add(new AvailabilityUpdate(eventSessionId, version));
            }
        }
        return drained;
    }

    public int pendingCount() {
        return pending.size();
    }
}
