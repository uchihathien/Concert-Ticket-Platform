// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import com.nexaticket.ticketing.domain.model.QrToken;
import java.util.Optional;

/**
 * Ký và kiểm token QR.
 *
 * <p>Là port để khoá riêng có thể chuyển sang KMS mà không đụng tới nghiệp vụ soát vé.
 */
public interface TicketSigner {

    /** @return chuỗi JWS compact, dạng {@code header.payload.signature} */
    String sign(QrToken token);

    /**
     * @return token nếu chữ ký hợp lệ và chưa hết hạn; rỗng trong mọi trường hợp còn lại
     */
    Optional<QrToken> verify(String compactToken);
}
