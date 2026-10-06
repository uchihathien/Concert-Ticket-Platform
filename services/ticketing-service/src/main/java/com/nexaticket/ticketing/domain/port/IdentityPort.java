// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Đọc hồ sơ người mua từ identity-service.
 *
 * <p>Dùng đúng <b>một</b> lần trong vòng đời một vé: lúc phát hành, để chụp lại tên người mua vào
 * {@code tickets.holder_name}. Không có đường đọc nào khác gọi tới đây — màn hình tra cứu của ban
 * tổ chức lọc theo tên, và lọc theo một cột phải là lọc trong SQL chứ không phải lọc sau khi đã
 * lấy hết dữ liệu về.
 */
public interface IdentityPort {

    /**
     * Tên hiển thị của một người dùng.
     *
     * <p>Trả {@link Optional#empty()} cho <b>mọi</b> tình huống không lấy được — không tồn tại,
     * mạng hỏng, identity đang sập. Ở đây ba thứ đó cùng một nghĩa, khác hẳn {@code OrderingPort}
     * nơi 404 và 5xx phải tách nhau vì một bên chặn hàng đợi còn một bên thì không.
     *
     * <p>Lý do gộp: tên khách là dữ liệu <b>tô điểm</b>. Vé vẫn phát được, vẫn quét được, vẫn vào
     * cửa được mà không có nó. Ném lỗi ở đây sẽ biến một service phụ im lặng thành "khách trả tiền
     * rồi mà không có vé" — đánh đổi ngược hoàn toàn.
     */
    Optional<String> displayNameOf(UUID userId);
}
