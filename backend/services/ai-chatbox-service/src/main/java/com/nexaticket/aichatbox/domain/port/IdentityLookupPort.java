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

    /**
     * Tên VÀ email của một người dùng.
     *
     * <p>Bàn hỗ trợ cần cả hai, không phải một. Tên để người trực biết đang nói với ai; email để
     * liên hệ lại khi khách đóng tab giữa chừng — phiếu bị bỏ dở sống 24 giờ (xem
     * {@code handoff.abandoned-after}), và trong 24 giờ đó cách duy nhất chạm được tới khách là địa
     * chỉ thư. Trước đây phiếu không mang thông tin nào về người hỏi, nên mọi phiếu trong hàng chờ
     * đều là "khách hàng" — người trực mở ra đọc mà không biết đang trả lời ai.
     *
     * <p>Trả {@code Optional.empty()} khi không tra được, KHÔNG ném: identity chập chờn thì bàn hỗ
     * trợ vẫn phải làm việc được, chỉ thiếu một cái tên.
     */
    default Optional<Contact> contactOf(UUID userId) {
        // Mặc định suy từ tên, KHÔNG phải để tiện: nó giữ cho interface còn đúng MỘT phương thức
        // trừu tượng, nên các test vẫn dựng được cổng này bằng một lambda. Bản thật
        // (IdentityHttpAdapter) ghi đè và trả cả email; bất kỳ cài đặt nào khác vẫn cho bàn hỗ trợ
        // một cái tên thay vì không gì cả.
        return displayNameOf(userId).map(name -> new Contact(name, null));
    }

    /** Thông tin liên hệ tối thiểu của một người dùng. */
    record Contact(String fullName, String email) {}
}
