// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import java.util.UUID;

/**
 * Ai đó gửi {@code sessionId} của người khác.
 *
 * <p>{@code sessionId} do client gửi lên, nên nó là dữ liệu chưa tin được — hệt như mọi id khác
 * đến từ ngoài. Không kiểm chủ sở hữu thì đọc được toàn bộ hội thoại hỗ trợ của người khác, trong
 * đó có mã đơn hàng và họ tên.
 */
public class SessionNotOwnedException extends RuntimeException {

    public SessionNotOwnedException(UUID sessionId) {
        super("Phiên " + sessionId + " không thuộc về người dùng hiện tại");
    }
}
