// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình bộ lọc Idempotency-Key cho mutation HTTP.
 *
 * <p>Mặc định rỗng = bộ lọc không làm gì. Từng service khai đúng những route nó muốn bảo vệ, thay vì
 * bộ lọc tự đoán "mọi POST": nhiều POST (đăng nhập, soát vé, webhook) không muốn, hoặc không thể,
 * mang khoá.
 *
 * @param requiredPaths mẫu đường dẫn kiểu Ant (ví dụ {@code /v1/orders}); chỉ áp cho {@code POST}
 * @param staleAfter bản ghi "đang xử lý" quá lâu được coi là của một tiến trình đã chết và được nhường
 *     cho request retry — nếu không, một lần sập giữa chừng khoá vĩnh viễn khoá đó
 * @param maxBodyBytes thân request lớn hơn mức này không băm được để so khớp, bị từ chối
 * @param retention bản ghi cũ hơn mức này bị job dọn xoá (ADR-0007: giữ ≥ 24 giờ; mặc định 48 giờ để có dư
 *     cho client thử lại sau một đêm mất mạng)
 * @param cleanupInterval khoảng cách giữa hai lần dọn
 * @param cleanupBatch số dòng xoá mỗi câu lệnh — chia lô để một lần dọn bảng lớn không giữ khoá lâu
 */
@ConfigurationProperties("nexaticket.idempotency")
public record IdempotencyProperties(
        List<String> requiredPaths,
        Duration staleAfter,
        Integer maxBodyBytes,
        Duration retention,
        Duration cleanupInterval,
        Integer cleanupBatch) {

    public IdempotencyProperties {
        requiredPaths = requiredPaths == null ? List.of() : List.copyOf(requiredPaths);
        staleAfter = staleAfter == null ? Duration.ofMinutes(2) : staleAfter;
        maxBodyBytes = maxBodyBytes == null ? 256 * 1024 : maxBodyBytes;
        retention = retention == null ? Duration.ofHours(48) : retention;
        cleanupInterval = cleanupInterval == null ? Duration.ofHours(1) : cleanupInterval;
        cleanupBatch = cleanupBatch == null ? 1000 : cleanupBatch;
    }
}
