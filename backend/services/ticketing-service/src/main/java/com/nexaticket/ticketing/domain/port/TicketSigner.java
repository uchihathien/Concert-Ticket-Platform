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

    /**
     * Như {@link #verify(String)}, nhưng kiểm hạn token tại thời điểm {@code at} thay vì bây giờ —
     * cho lượt quét offline đồng bộ muộn: mã còn hạn lúc máy quét thì là mã hợp lệ, kể cả khi lúc gửi
     * lên nó đã hết hạn.
     */
    Optional<QrToken> verify(String compactToken, java.time.Instant at);

    /**
     * Mọi khoá CÔNG KHAI còn dùng để kiểm (cả khoá đã nghỉ hưu), dạng Ed25519 thô 32 byte base64url —
     * máy soát vé tải về để tự kiểm chữ ký QR khi mất mạng.
     */
    java.util.List<VerificationKey> verificationKeys();

    record VerificationKey(String kid, String publicKey) {}
}
