// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.application.TicketingErrorCode;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.infrastructure.crypto.Ed25519TicketSigner;
import com.nexaticket.ticketing.infrastructure.crypto.SigningKeyStore;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Ký và kiểm token QR bằng Ed25519 — không cần database.
 *
 * <p>{@code QrTokenIT} đã phủ đường ký-rồi-kiểm, sửa một ký tự, hết hạn và xoay khoá với khoá thật
 * trong PostgreSQL. Ở đây là những đòn tấn công cụ thể hơn vào chính định dạng JWS tự dựng: khoá
 * sai, {@code kid} lạ, {@code alg: none}, ghép chữ ký của vé này vào nội dung của vé khác.
 */
class TicketSigningTest {

    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final ObjectMapper json = new ObjectMapper();
    private SigningKeyStore keys;
    private Ed25519TicketSigner signer;
    private SigningKeyStore.Key active;

    @BeforeEach
    void setUp() {
        keys = mock(SigningKeyStore.class);
        active = key("kid-moi");
        when(keys.active()).thenReturn(active);
        when(keys.byKid(anyString())).thenReturn(Optional.empty());
        when(keys.byKid("kid-moi")).thenReturn(Optional.of(active));
        signer = new Ed25519TicketSigner(keys, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("Ký rồi kiểm")
    class KyRoiKiem {

        @Test
        @DisplayName("Token vừa ký kiểm lại được, đúng jti và exp; header mang alg EdDSA và kid của khoá")
        void ky_roi_kiem() throws Exception {
            QrToken token = new QrToken(UUID.randomUUID(), NOW.getEpochSecond() + 60);

            String compact = signer.sign(token);

            assertThat(signer.verify(compact)).contains(token);
            String header = new String(Base64.getUrlDecoder().decode(compact.split("\\.")[0]), StandardCharsets.UTF_8);
            assertThat(json.readTree(header).get("alg").asText()).isEqualTo("EdDSA");
            assertThat(json.readTree(header).get("kid").asText()).isEqualTo("kid-moi");
        }

        @Test
        @DisplayName("Khoá đã nghỉ hưu, chỉ còn khoá công khai, vẫn kiểm được vé cũ")
        void khoa_nghi_huu_chi_con_khoa_cong_khai() {
            QrToken token = new QrToken(UUID.randomUUID(), NOW.getEpochSecond() + 60);
            String compact = signer.sign(token);
            when(keys.byKid("kid-moi"))
                    .thenReturn(Optional.of(new SigningKeyStore.Key("kid-moi", null, active.publicKey())));

            assertThat(signer.verify(compact)).contains(token);
        }
    }

    @Nested
    @DisplayName("Vé giả")
    class VeGia {

        @Test
        @DisplayName("Ký bằng khoá khác nhưng mạo danh kid của hệ thống: từ chối")
        void khoa_sai_mao_danh_kid() {
            SigningKeyStore attacker = mock(SigningKeyStore.class);
            SigningKeyStore.Key forged = key("kid-moi");
            when(attacker.active()).thenReturn(forged);
            String compact = new Ed25519TicketSigner(attacker, json, Clock.fixed(NOW, ZoneOffset.UTC))
                    .sign(new QrToken(UUID.randomUUID(), NOW.getEpochSecond() + 60));

            assertThat(signer.verify(compact)).isEmpty();
        }

        @Test
        @DisplayName("kid không có trong kho: từ chối")
        void kid_la() {
            SigningKeyStore other = mock(SigningKeyStore.class);
            when(other.active()).thenReturn(key("kid-la"));
            String compact = new Ed25519TicketSigner(other, json, Clock.fixed(NOW, ZoneOffset.UTC))
                    .sign(new QrToken(UUID.randomUUID(), NOW.getEpochSecond() + 60));

            assertThat(signer.verify(compact)).isEmpty();
        }

        @Test
        @DisplayName("Ghép chữ ký của vé A vào nội dung của vé B: từ chối")
        void ghep_chu_ky() {
            String a = signer.sign(new QrToken(UUID.randomUUID(), NOW.getEpochSecond() + 60));
            String b = signer.sign(new QrToken(UUID.randomUUID(), NOW.getEpochSecond() + 60));
            String[] pa = a.split("\\.");
            String[] pb = b.split("\\.");

            assertThat(signer.verify(pb[0] + "." + pb[1] + "." + pa[2])).isEmpty();
        }

        @Test
        @DisplayName("Kéo dài hạn bằng cách sửa exp trong payload: từ chối")
        void sua_exp() {
            UUID jti = UUID.randomUUID();
            String[] parts =
                    signer.sign(new QrToken(jti, NOW.getEpochSecond() + 60)).split("\\.");
            String extended =
                    URL.encodeToString(("{\"jti\":\"" + jti + "\",\"exp\":" + (NOW.getEpochSecond() + 999_999) + "}")
                            .getBytes(StandardCharsets.UTF_8));

            assertThat(signer.verify(parts[0] + "." + extended + "." + parts[2]))
                    .isEmpty();
        }

        @Test
        @DisplayName("alg: none không chữ ký: từ chối")
        void alg_none() {
            String header =
                    URL.encodeToString("{\"alg\":\"none\",\"kid\":\"kid-moi\"}".getBytes(StandardCharsets.UTF_8));
            String payload = URL.encodeToString(
                    ("{\"jti\":\"" + UUID.randomUUID() + "\",\"exp\":" + (NOW.getEpochSecond() + 60) + "}")
                            .getBytes(StandardCharsets.UTF_8));

            assertThat(signer.verify(header + "." + payload + ".")).isEmpty();
            assertThat(signer.verify(header + "." + payload)).isEmpty();
        }

        @Test
        @DisplayName("Header không có kid, hoặc không phải JSON: từ chối, không ném")
        void header_hong() {
            String[] parts = signer.sign(new QrToken(UUID.randomUUID(), NOW.getEpochSecond() + 60))
                    .split("\\.");
            String noKid = URL.encodeToString("{\"alg\":\"EdDSA\"}".getBytes(StandardCharsets.UTF_8));

            assertThat(signer.verify(noKid + "." + parts[1] + "." + parts[2])).isEmpty();
            assertThat(signer.verify("!!!." + parts[1] + "." + parts[2])).isEmpty();
            assertThat(signer.verify(null)).isEmpty();
            assertThat(signer.verify("")).isEmpty();
        }

        @Test
        @DisplayName("Hết hạn đúng tại giây exp: từ chối")
        void het_han_dung_giay_exp() {
            assertThat(signer.verify(signer.sign(new QrToken(UUID.randomUUID(), NOW.getEpochSecond()))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Kho khoá")
    class KhoKhoa {

        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final SigningKeyStore store = new SigningKeyStore(jdbc);

        @Test
        @DisplayName("Chưa có khoá đang hoạt động: ký bị từ chối với NO_SIGNING_KEY")
        @SuppressWarnings("unchecked")
        void khong_co_khoa() {
            when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

            assertThatThrownBy(store::active).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                    .isEqualTo(TicketingErrorCode.NO_SIGNING_KEY));
        }

        @Test
        @DisplayName("Tra theo kid: lần đầu đọc database, các lần sau đọc cache")
        @SuppressWarnings("unchecked")
        void tra_theo_kid_co_cache() {
            SigningKeyStore.Key stored = key("k1");
            when(jdbc.query(anyString(), any(RowMapper.class), eq("k1"))).thenReturn(List.of(stored));

            assertThat(store.byKid("k1")).contains(stored);
            assertThat(store.byKid("k1")).contains(stored);

            verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), eq("k1"));
        }

        @Test
        @DisplayName("kid không tồn tại không được cache: khoá mới thêm vào thì thấy ngay")
        @SuppressWarnings("unchecked")
        void kid_khong_ton_tai_khong_cache() {
            SigningKeyStore.Key stored = key("k2");
            when(jdbc.query(anyString(), any(RowMapper.class), eq("k2")))
                    .thenReturn(List.of())
                    .thenReturn(List.of(stored));

            assertThat(store.byKid("k2")).isEmpty();
            assertThat(store.byKid("k2")).contains(stored);
        }

        @Test
        @DisplayName("Khoá đang hoạt động được đưa vào cache để verify không phải đọc database")
        @SuppressWarnings("unchecked")
        void khoa_dang_hoat_dong_vao_cache() {
            SigningKeyStore.Key stored = key("k3");
            when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(stored));

            store.active();

            assertThat(store.byKid("k3")).contains(stored);
            verify(jdbc, times(0)).query(anyString(), any(RowMapper.class), eq("k3"));
        }
    }

    private static SigningKeyStore.Key key(String kid) {
        KeyPair pair = Ed25519TicketSigner.generateKeyPair();
        return new SigningKeyStore.Key(
                kid,
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }
}
