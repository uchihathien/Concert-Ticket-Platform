// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ký token QR bằng Ed25519, định dạng JWS compact.
 *
 * <h2>Vì sao Ed25519, không phải RSA hay HMAC</h2>
 *
 * <ul>
 *   <li>Chữ ký 64 byte cho token khoảng 180 ký tự. RSA-2048 cho chữ ký 256 byte, mã QR dày đặc
 *       hơn hẳn và quét chậm trong điều kiện ánh sáng kém ở cửa vào — nơi có hàng nghìn người xếp
 *       hàng và mỗi giây đều tính.
 *   <li>Bất đối xứng nên máy soát vé chỉ cần khoá công khai. Với HMAC, mỗi thiết bị soát vé phải
 *       giữ khoá ký được, và mất một máy là in được vé giả.
 * </ul>
 *
 * <p>Tự dựng JWS thay vì kéo thêm thư viện JWT: định dạng chỉ là ba đoạn base64url nối bằng dấu
 * chấm, và mọi thứ cần dùng đều có sẵn trong JDK 17+. Một dependency ít đi là một đường tấn công
 * chuỗi cung ứng ít đi, ở đúng chỗ nhạy cảm nhất.
 */
@Component
public class Ed25519TicketSigner implements TicketSigner {

    private static final Logger log = LoggerFactory.getLogger(Ed25519TicketSigner.class);
    private static final String ALGORITHM = "Ed25519";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final SigningKeyStore keys;
    private final ObjectMapper json;
    private final Clock clock;

    public Ed25519TicketSigner(SigningKeyStore keys, ObjectMapper json, Clock clock) {
        this.keys = keys;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String sign(QrToken token) {
        SigningKeyStore.Key key = keys.active();
        String header = encode(write(Map.of("alg", "EdDSA", "typ", "JWT", "kid", key.kid())));
        String payload = encode(write(Map.of("jti", token.jti().toString(), "exp", token.expiresAtEpochSecond())));
        String signingInput = header + "." + payload;

        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey(key.privateKey()));
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + URL_ENCODER.encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Không ký được token vé", e);
        }
    }

    @Override
    public Optional<QrToken> verify(String compactToken) {
        // Mọi đường hỏng đều trả rỗng, không ném và không log chi tiết: đầu vào ở đây là thứ ai
        // cũng gửi được, và một thông báo lỗi chi tiết chỉ giúp người làm vé giả dò dần.
        if (compactToken == null) {
            return Optional.empty();
        }
        String[] parts = compactToken.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            Map<?, ?> header = json.readValue(URL_DECODER.decode(parts[0]), Map.class);
            Object kid = header.get("kid");
            if (kid == null) {
                return Optional.empty();
            }

            // Tra khoá theo kid: vé cũ vẫn verify được bằng khoá đã nghỉ hưu, nên xoay khoá
            // KHÔNG vô hiệu vé đã phát hành.
            Optional<SigningKeyStore.Key> key = keys.byKid(kid.toString());
            if (key.isEmpty()) {
                return Optional.empty();
            }

            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey(key.get().publicKey()));
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!signature.verify(URL_DECODER.decode(parts[2]))) {
                return Optional.empty();
            }

            Map<?, ?> payload = json.readValue(URL_DECODER.decode(parts[1]), Map.class);
            QrToken token = new QrToken(
                    UUID.fromString(payload.get("jti").toString()),
                    Long.parseLong(payload.get("exp").toString()));
            return token.isExpiredAt(clock.instant().getEpochSecond()) ? Optional.empty() : Optional.of(token);
        } catch (Exception e) {
            log.debug("Token QR không hợp lệ", e);
            return Optional.empty();
        }
    }

    /** Sinh cặp khoá mới. Dùng khi khởi tạo môi trường và khi xoay khoá. */
    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("JVM không hỗ trợ Ed25519", e);
        }
    }

    private static PrivateKey privateKey(String base64) {
        try {
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalStateException("Khoá riêng không đọc được", e);
        }
    }

    private static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalStateException("Khoá công khai không đọc được", e);
        }
    }

    private byte[] write(Map<String, Object> value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("Không serialize được JWS", e);
        }
    }

    private static String encode(byte[] raw) {
        return URL_ENCODER.encodeToString(raw);
    }
}
