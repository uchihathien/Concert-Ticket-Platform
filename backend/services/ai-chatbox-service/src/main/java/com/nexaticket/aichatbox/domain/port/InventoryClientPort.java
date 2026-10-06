// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.TicketHold;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import java.util.UUID;

/**
 * Giữ chỗ ở inventory-service, <b>thay mặt khách</b>.
 *
 * <p>Cùng mô hình tin cậy với {@link OrderingClientPort}: đi bằng access token của chính khách qua
 * đường công khai {@code POST /v1/sessions/{id}/holds}. Hạn mức mua của một người, số chỗ còn
 * trống, thời gian giữ — tất cả do inventory quyết như với mọi client khác; agent không được giao
 * và không có khả năng kiểm những thứ đó.
 *
 * <p>Agent chỉ giữ chỗ <b>theo khu</b>, không chỉ đích danh ghế: khách không nhìn thấy sơ đồ trong
 * khung chat, nên "ghế A12" là một chuỗi mô hình tự nghĩ ra. Hệ thống chọn chỗ hộ, gần sân khấu
 * trước, như nút "chọn nhanh" trên trang web.
 */
public interface InventoryClientPort {

    /**
     * @param idempotencyKey khoá chống giữ hai lần — inventory bắt buộc có. Tầng gọi sinh một khoá
     *     cho mỗi lời gọi tool, nên cùng một lượt chat chạy lại không giữ thêm chỗ
     * @throws BookingRejectedException inventory từ chối vì lý do nghiệp vụ (hết chỗ, vượt hạn mức,
     *     suất đã đóng bán) — mang theo câu để nói với khách
     * @throws RemoteCallException inventory quá hạn hoặc trả 5xx
     */
    TicketHold holdZone(
            UUID eventSessionId,
            String zoneCode,
            int quantity,
            ZoneAdmission admission,
            String callerAccessToken,
            String idempotencyKey);

    /**
     * Nhả một chỗ vừa giữ — dùng khi bước đặt đơn ngay sau đó hỏng.
     *
     * <p>Không ném: chỗ không nhả được sẽ tự hết hạn sau 10 phút, và lỗi ở bước dọn không được
     * phép che mất lỗi thật ở bước đặt đơn.
     */
    void releaseHold(UUID holdId, String callerAccessToken);
}
