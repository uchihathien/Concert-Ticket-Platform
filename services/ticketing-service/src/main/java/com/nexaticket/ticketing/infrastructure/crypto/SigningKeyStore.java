// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure.crypto;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.application.TicketingErrorCode;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Kho khoá ký, có cache trong bộ nhớ.
 *
 * <p>Cache là bắt buộc chứ không phải tối ưu vặt: mỗi lần mở ví vé ký lại token cho từng vé, và
 * mỗi lần soát vé lại verify một token. Đọc database cho mỗi thao tác ký sẽ biến khoá ký thành
 * điểm nghẽn ở đúng lúc đông nhất — giờ mở cửa.
 *
 * <p>Cache an toàn vì khoá là bất biến: xoay khoá tạo ra một {@code kid} mới, không sửa khoá cũ.
 *
 * <p><b>Khoá riêng nằm trong database là điểm yếu đã biết.</b> Ở production nó phải nằm trong KMS
 * và bảng chỉ giữ khoá công khai; port {@code TicketSigner} tồn tại chính là để đổi được chỗ đó
 * mà không đụng tới nghiệp vụ soát vé.
 */
@Component
public class SigningKeyStore {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyStore.class);

    private final JdbcTemplate jdbc;
    private final ConcurrentMap<String, Key> cache = new ConcurrentHashMap<>();

    public SigningKeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param privateKey null với khoá chỉ dùng để verify
     */
    public record Key(String kid, String privateKey, String publicKey) {}

    public Key active() {
        List<Key> found = jdbc.query(
                "SELECT kid, private_key, public_key FROM signing_keys WHERE is_active",
                (rs, i) -> new Key(rs.getString("kid"), rs.getString("private_key"), rs.getString("public_key")));
        if (found.isEmpty()) {
            throw new ApiException(TicketingErrorCode.NO_SIGNING_KEY, "No active ticket signing key is configured");
        }
        Key key = found.get(0);
        cache.put(key.kid(), key);
        return key;
    }

    public Optional<Key> byKid(String kid) {
        Key cached = cache.get(kid);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Key> found = jdbc
                .query(
                        "SELECT kid, private_key, public_key FROM signing_keys WHERE kid = ?",
                        (rs, i) ->
                                new Key(rs.getString("kid"), rs.getString("private_key"), rs.getString("public_key")),
                        kid)
                .stream()
                .findFirst();
        found.ifPresent(key -> cache.put(key.kid(), key));
        return found;
    }

    /**
     * Sinh và kích hoạt một khoá mới.
     *
     * <p>Khoá cũ chỉ bị bỏ cờ {@code is_active} chứ <b>không bị xoá</b>: vé đã phát hành vẫn mang
     * {@code kid} cũ trong header và vẫn phải verify được, nếu không thì xoay khoá đồng nghĩa với
     * vô hiệu mọi vé đang lưu hành.
     */
    public Key rotate() {
        KeyPair pair = Ed25519TicketSigner.generateKeyPair();
        String kid = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("UPDATE signing_keys SET is_active = FALSE, retired_at = now() WHERE is_active");
        jdbc.update(
                """
                INSERT INTO signing_keys (kid, private_key, public_key, is_active)
                VALUES (?, ?, ?, TRUE)
                """,
                kid,
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        log.info("Đã kích hoạt khoá ký vé mới: kid={}", kid);
        cache.clear();
        return active();
    }

    /** Có khoá nào chưa; dùng khi khởi động môi trường dev và trong test. */
    public boolean hasActiveKey() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM signing_keys WHERE is_active", Integer.class);
        return count != null && count > 0;
    }
}
