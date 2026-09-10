// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

import com.nexaticket.aichatbox.domain.model.OrderSummary;
import java.util.UUID;

/**
 * Tra đơn hàng ở ordering-service.
 *
 * <h2>Vì sao đi bằng token của khách, không phải bí mật nội bộ</h2>
 *
 * ordering-service có hai đường đọc một đơn:
 *
 * <ul>
 *   <li>{@code GET /v1/orders/{orderId}} — đi qua {@code byIdForUser(orderId, userId)}: <b>chỉ trả
 *       đơn của chính người gọi</b>, và chỉ những trường khách được thấy.
 *   <li>{@code GET /internal/orders/{orderId}} — Open Host Service cho ledger và payout. <b>Không
 *       kiểm chủ sở hữu</b> (bên gọi là service, không phải người), và bản trả về có hoa hồng nền
 *       tảng.
 * </ul>
 *
 * Đường thứ hai là đường sai ở đây, và sai theo cách nguy hiểm nhất: nó chạy được. Agent gọi nó
 * bằng {@code X-Internal-Token} thì <b>mọi</b> mã đơn đều tra được — khách chỉ cần hỏi "đơn
 * 3f2a…-… của tôi sao rồi?" với một mã không phải của mình, và mô hình, vốn không có cách nào biết
 * ai sở hữu đơn nào, sẽ đọc to nội dung đơn đó lên. Không có log nào báo động, vì mọi lời gọi đều
 * hợp lệ về mặt kỹ thuật.
 *
 * <p>Đi bằng access token của chính khách thì <b>ordering-service</b> quyết định quyền, đúng chỗ
 * nó vẫn quyết định cho mọi client khác. Mô hình không được giao việc phân quyền — nó không có
 * khả năng làm việc đó, và một prompt "chỉ tra đơn của người đang hỏi" là lời nhắc, không phải
 * chốt chặn.
 */
public interface OrderingClientPort {

    /**
     * @param callerAccessToken bearer token của <b>người đang chat</b>, lấy từ request hiện tại
     * @throws OrderNotFoundException đơn không tồn tại <b>hoặc</b> không thuộc về người gọi — hai
     *     tình huống này cố ý không phân biệt được, vì phân biệt được nghĩa là dò ra mã đơn có thật
     * @throws RemoteCallException ordering-service quá hạn, từ chối, hoặc trả 5xx
     */
    OrderSummary fetchOrder(UUID orderId, String callerAccessToken);
}
