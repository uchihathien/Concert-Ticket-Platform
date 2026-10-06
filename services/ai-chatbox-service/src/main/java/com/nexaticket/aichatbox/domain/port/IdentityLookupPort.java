// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Tra tên người trực để hiển thị.
 *
 * <h2>Chỉ để HIỂN THỊ, không để phân quyền</h2>
 *
 * Quyền của người trực do {@code SupportDeskAccess} quyết định từ token, không từ cổng này. Cổng
 * này chỉ đổi một UUID thành một cái tên đọc được — và đó là lý do nó <b>không bao giờ ném ngoại
 * lệ</b>: identity-service sập thì hàng đợi hỗ trợ vẫn phải mở được, chỉ là mỗi phiếu hiện "nhân
 * viên hỗ trợ" thay cho tên riêng. Đánh sập cả bàn hỗ trợ vì không tra được một cái tên là đổi một
 * khiếm khuyết nhỏ thành một sự cố.
 *
 * <h2>Chỉ tra tên NGƯỜI TRỰC, không tra tên khách</h2>
 *
 * Khách trong một phiếu hỗ trợ được nhận ra bằng phiên chat, và người trực đã đọc được toàn bộ
 * những gì khách nói. Kéo thêm tên và email khách vào đây là mở rộng phạm vi dữ liệu cá nhân đi qua
 * service này mà không trả lời thêm được câu hỏi nào của người trực.
 */
public interface IdentityLookupPort {

    /**
     * @return tên đầy đủ để hiện lên màn hình, hoặc rỗng khi không tra được — <b>vì bất kỳ lý do
     *     gì</b>: không có người ấy, identity quá hạn, hay mạng hỏng. Bên gọi xử lý cả ba như nhau,
     *     nên phân biệt chúng ở đây chỉ tạo ra ba nhánh làm cùng một việc.
     */
    Optional<String> displayNameOf(UUID userId);
}
