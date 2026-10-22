// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.http;

import static com.nexaticket.identity.infrastructure.http.FakeHttpServer.created;
import static com.nexaticket.identity.infrastructure.http.FakeHttpServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.domain.port.IdentityProviderPort;
import com.nexaticket.identity.domain.port.IdentityProviderPort.IdentityProviderUnavailableException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Anti-Corruption Layer sang Keycloak Admin API: request đi ra có đúng hình dạng không.
 *
 * <p>Không có Keycloak thật; {@link FakeHttpServer} trả lời ngay trong tiến trình. Thứ đáng kiểm ở
 * đây là <b>ánh xạ</b> — đường dẫn, tham số, thân request — vì Keycloak từ chối sai lệch nhỏ nhất bằng
 * một mã 400 không nói gì, và lỗi kiểu đó chỉ lộ ra khi có người bấm nút trên production.
 */
class KeycloakAdminAdapterTest {

    private static final String REALM_USERS = "/admin/realms/nexaticket/users";
    private static final String TOKEN_PATH = "/realms/nexaticket/protocol/openid-connect/token";

    private final ObjectMapper json = new ObjectMapper();
    private final FakeHttpServer keycloak = new FakeHttpServer();
    private KeycloakAdminAdapter adapter;

    private static KeycloakAdminProperties configured() {
        return new KeycloakAdminProperties(
                "http://keycloak.test", null, null, "bi-mat", "web-admin", "http://localhost:3001/login", null);
    }

    @BeforeEach
    void setUp() {
        adapter = new KeycloakAdminAdapter(configured(), keycloak.builder());
        keycloak.respondWith(call -> {
            if (call.path().equals(TOKEN_PATH)) {
                return respond(HttpStatus.OK, "{\"access_token\":\"tok-1\",\"expires_in\":300}");
            }
            return respond(HttpStatus.NO_CONTENT, "");
        });
    }

    @Nested
    @DisplayName("thư đặt lại mật khẩu")
    class DatLaiMatKhau {

        @Test
        @DisplayName(
                "PUT execute-actions-email đúng tài khoản, chỉ UPDATE_PASSWORD, kèm client_id và redirect_uri mặc định")
        void anh_xa_request() throws Exception {
            adapter.sendPasswordResetEmail("sub-1", null, null);

            FakeHttpServer.Call call =
                    keycloak.callsTo(HttpMethod.PUT, REALM_USERS).get(0);
            assertThat(call.path()).isEqualTo(REALM_USERS + "/sub-1/execute-actions-email");
            var query = UriComponentsBuilder.fromUri(call.uri()).build().getQueryParams();
            assertThat(query.getFirst("client_id")).isEqualTo("web-admin");
            assertThat(call.uri().getQuery()).contains("redirect_uri=http://localhost:3001/login");
            assertThat(call.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok-1");
            // Đúng MỘT hành động: thêm VERIFY_EMAIL là âm thầm đổi cả trạng thái xác minh email.
            assertThat(json.readTree(call.body())).containsExactly(json.readTree("\"UPDATE_PASSWORD\""));
        }

        @Test
        @DisplayName("token service account lấy bằng client_credentials với đúng client và bí mật")
        void lay_token() {
            adapter.sendPasswordResetEmail("sub-1", null, null);

            FakeHttpServer.Call token =
                    keycloak.callsTo(HttpMethod.POST, TOKEN_PATH).get(0);
            assertThat(token.body())
                    .contains("grant_type=client_credentials")
                    .contains("client_id=identity-admin")
                    .contains("client_secret=bi-mat");
            assertThat(token.headers().getContentType()).hasToString("application/x-www-form-urlencoded");
        }

        @Test
        @DisplayName("token được dùng lại giữa hai lời gọi, không xin mới mỗi lần")
        void cache_token() {
            adapter.sendPasswordResetEmail("sub-1", null, null);
            adapter.sendPasswordResetEmail("sub-2", null, null);

            assertThat(keycloak.callsTo(HttpMethod.POST, TOKEN_PATH)).hasSize(1);
            assertThat(keycloak.callsTo(HttpMethod.PUT, REALM_USERS)).hasSize(2);
        }

        @Test
        @DisplayName("chỉ một trong hai tham số quay về thì không gửi tham số nào — Keycloak bỏ qua lẻ một cái")
        void tham_so_di_cung_nhau() {
            KeycloakAdminAdapter withoutRedirect = new KeycloakAdminAdapter(
                    new KeycloakAdminProperties("http://keycloak.test", null, null, "bi-mat", "web-admin", null, null),
                    keycloak.builder());

            withoutRedirect.sendPasswordResetEmail("sub-1", null, null);

            FakeHttpServer.Call call =
                    keycloak.callsTo(HttpMethod.PUT, REALM_USERS).get(0);
            assertThat(call.uri().getQuery()).isNull();
        }

        @Test
        @DisplayName("chưa cấu hình bí mật: lỗi 'không sẵn sàng' có nội dung, không có lời gọi mạng nào")
        void chua_cau_hinh() {
            KeycloakAdminAdapter unconfigured = new KeycloakAdminAdapter(
                    new KeycloakAdminProperties("http://keycloak.test", null, null, " ", null, null, null),
                    keycloak.builder());

            assertThatThrownBy(() -> unconfigured.sendPasswordResetEmail("sub-1", null, null))
                    .isInstanceOf(IdentityProviderUnavailableException.class)
                    .hasMessageContaining("Chưa cấu hình");
            assertThatThrownBy(() -> unconfigured.createInvitedAccount("a@example.com", "A"))
                    .isInstanceOf(IdentityProviderUnavailableException.class);
            assertThat(keycloak.calls()).isEmpty();
        }

        @Test
        @DisplayName("Keycloak trả 400: lỗi mang nguyên văn thân lỗi để người vận hành lần ra")
        void loi_400() {
            keycloak.respondWith(call -> call.path().equals(TOKEN_PATH)
                    ? respond(HttpStatus.OK, "{\"access_token\":\"tok-1\",\"expires_in\":300}")
                    : respond(HttpStatus.BAD_REQUEST, "{\"error\":\"Invalid redirect uri\"}"));

            assertThatThrownBy(() -> adapter.sendPasswordResetEmail("sub-1", null, null))
                    .isInstanceOf(IdentityProviderUnavailableException.class)
                    .hasMessageContaining("Invalid redirect uri");
        }

        @Test
        @DisplayName("tài khoản không có bên Keycloak (404) và mất kết nối đều thành 'không sẵn sàng'")
        void loi_404_va_mat_ket_noi() {
            keycloak.respondWith(call -> call.path().equals(TOKEN_PATH)
                    ? respond(HttpStatus.OK, "{\"access_token\":\"tok-1\",\"expires_in\":300}")
                    : respond(HttpStatus.NOT_FOUND, ""));
            assertThatThrownBy(() -> adapter.sendPasswordResetEmail("sub-x", null, null))
                    .isInstanceOf(IdentityProviderUnavailableException.class)
                    .hasMessageContaining("sub-x");

            keycloak.respondWith(call -> null);
            KeycloakAdminAdapter fresh = new KeycloakAdminAdapter(configured(), keycloak.builder());
            assertThatThrownBy(() -> fresh.sendPasswordResetEmail("sub-1", null, null))
                    .isInstanceOf(IdentityProviderUnavailableException.class);
        }

        @Test
        @DisplayName("id tài khoản mang ký tự đường dẫn không thoát ra khỏi /users/{id}")
        void id_khong_thoat_duong_dan() {
            adapter.sendPasswordResetEmail("abc/../../roles", null, null);

            FakeHttpServer.Call call =
                    keycloak.callsTo(HttpMethod.PUT, REALM_USERS).get(0);
            assertThat(call.path())
                    .startsWith(REALM_USERS + "/abc%2F..%2F..%2Froles/")
                    .endsWith("/execute-actions-email");
        }
    }

    @Nested
    @DisplayName("tài khoản nhân viên soát vé")
    class TaiKhoanNhanVien {

        @Test
        @DisplayName("tạo tài khoản không mật khẩu, buộc đặt mật khẩu và xác minh email; id lấy từ Location")
        void tao_tai_khoan() throws Exception {
            keycloak.respondWith(call -> call.path().equals(TOKEN_PATH)
                    ? respond(HttpStatus.OK, "{\"access_token\":\"tok-1\",\"expires_in\":300}")
                    : created("http://keycloak.test/admin/realms/nexaticket/users/kc-id-42"));

            String id = adapter.createInvitedAccount("nv@example.com", "Nguyễn Văn An");

            assertThat(id).isEqualTo("kc-id-42");
            FakeHttpServer.Call call =
                    keycloak.callsTo(HttpMethod.POST, REALM_USERS).get(0);
            assertThat(call.path()).isEqualTo(REALM_USERS);
            JsonNode body = json.readTree(call.body());
            assertThat(body.get("username").asText()).isEqualTo("nv@example.com");
            assertThat(body.get("email").asText()).isEqualTo("nv@example.com");
            assertThat(body.get("firstName").asText()).isEqualTo("An");
            assertThat(body.get("lastName").asText()).isEqualTo("Nguyễn Văn");
            assertThat(body.get("enabled").asBoolean()).isTrue();
            assertThat(body.get("emailVerified").asBoolean()).isFalse();
            assertThat(body.has("credentials"))
                    .as("tài khoản ra đời KHÔNG có mật khẩu")
                    .isFalse();
            assertThat(body.get("requiredActions")).hasSize(2);
            assertThat(body.get("requiredActions").toString()).contains("UPDATE_PASSWORD", "VERIFY_EMAIL");
        }

        @Test
        @DisplayName("Keycloak tạo xong mà không trả Location thì báo lỗi thay vì trả id rỗng")
        void khong_co_location() {
            keycloak.respondWith(call -> call.path().equals(TOKEN_PATH)
                    ? respond(HttpStatus.OK, "{\"access_token\":\"tok-1\",\"expires_in\":300}")
                    : created(null));

            assertThatThrownBy(() -> adapter.createInvitedAccount("nv@example.com", "An"))
                    .isInstanceOf(IdentityProviderUnavailableException.class);
        }

        @Test
        @DisplayName("email trùng (409) thành lỗi 'không sẵn sàng' kèm thân lỗi")
        void email_trung() {
            keycloak.respondWith(call -> call.path().equals(TOKEN_PATH)
                    ? respond(HttpStatus.OK, "{\"access_token\":\"tok-1\",\"expires_in\":300}")
                    : respond(HttpStatus.CONFLICT, "{\"errorMessage\":\"User exists with same email\"}"));

            assertThatThrownBy(() -> adapter.createInvitedAccount("nv@example.com", "An"))
                    .isInstanceOf(IdentityProviderUnavailableException.class)
                    .hasMessageContaining("same email");
        }

        @Test
        @DisplayName("thư mời: cả hai hành động, hạn liên kết tính bằng giây")
        void thu_moi() throws Exception {
            adapter.sendInvitationEmail("kc-1", Duration.ofHours(72));

            FakeHttpServer.Call call =
                    keycloak.callsTo(HttpMethod.PUT, REALM_USERS).get(0);
            assertThat(call.path()).isEqualTo(REALM_USERS + "/kc-1/execute-actions-email");
            assertThat(call.uri().getQuery()).isEqualTo("lifespan=259200");
            assertThat(json.readTree(call.body()).toString()).contains("UPDATE_PASSWORD", "VERIFY_EMAIL");
        }

        @Test
        @DisplayName("đọc tài khoản: còn UPDATE_PASSWORD là chưa kích hoạt; 404 là không có")
        void doc_tai_khoan() {
            keycloak.respondWith(call -> {
                if (call.path().equals(TOKEN_PATH)) {
                    return respond(HttpStatus.OK, "{\"access_token\":\"tok-1\",\"expires_in\":300}");
                }
                if (call.path().endsWith("/pending")) {
                    return respond(
                            HttpStatus.OK, "{\"id\":\"pending\",\"requiredActions\":[\"UPDATE_PASSWORD\"],\"x\":1}");
                }
                if (call.path().endsWith("/active")) {
                    return respond(HttpStatus.OK, "{\"id\":\"active\",\"requiredActions\":[]}");
                }
                return respond(HttpStatus.NOT_FOUND, "");
            });

            assertThat(adapter.findAccount("pending")).contains(new IdentityProviderPort.Account("pending", false));
            assertThat(adapter.findAccount("active")).contains(new IdentityProviderPort.Account("active", true));
            assertThat(adapter.findAccount("missing")).isEmpty();
        }

        @Test
        @DisplayName("tra theo email dùng exact=true — không thì 'an@x.vn' khớp cả 'lan@x.vn'")
        void tra_theo_email_chinh_xac() {
            keycloak.respondWith(call -> call.path().equals(TOKEN_PATH)
                    ? respond(HttpStatus.OK, "{\"access_token\":\"tok-1\",\"expires_in\":300}")
                    : respond(HttpStatus.OK, "[]"));

            Optional<IdentityProviderPort.Account> found = adapter.findAccountByEmail("an@x.vn");

            assertThat(found).isEmpty();
            FakeHttpServer.Call call =
                    keycloak.callsTo(HttpMethod.GET, REALM_USERS).get(0);
            var query = UriComponentsBuilder.fromUri(call.uri()).build().getQueryParams();
            assertThat(query.getFirst("exact")).isEqualTo("true");
            // getQueryParams giữ nguyên dạng đã mã hoá ("@" thành %40, hợp lệ) — giải mã rồi mới so.
            assertThat(org.springframework.web.util.UriUtils.decode(
                            query.getFirst("email"), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("an@x.vn");
        }

        @Test
        @DisplayName("email có dấu '+' được mã hoá — Keycloak đọc '+' thô trong query thành khoảng trắng")
        void email_co_dau_cong() {
            // "nv+soatve@example.com" là địa chỉ hợp lệ và phổ biến (Gmail). Gửi '+' thô thì phía máy
            // chủ giải mã nó thành khoảng trắng, tra không ra tài khoản khách đã tự đăng ký, và luồng
            // mời nhân viên đi tiếp tới bước tạo tài khoản rồi nhận 409 từ Keycloak — 503 cho một
            // email hoàn toàn bình thường, thay vì EMAIL_NOT_INVITABLE.
            keycloak.respondWith(call -> call.path().equals(TOKEN_PATH)
                    ? respond(HttpStatus.OK, "{\"access_token\":\"tok-1\",\"expires_in\":300}")
                    : respond(HttpStatus.OK, "[]"));

            adapter.findAccountByEmail("nv+soatve@example.com");

            FakeHttpServer.Call call =
                    keycloak.callsTo(HttpMethod.GET, REALM_USERS).get(0);
            assertThat(call.uri().getRawQuery()).contains("%2B").doesNotContain("+");
        }

        @Test
        @DisplayName("không xoá tài khoản đã kích hoạt; tài khoản đã mất thì coi như xong")
        void xoa_tai_khoan() {
            keycloak.respondWith(call -> {
                if (call.path().equals(TOKEN_PATH)) {
                    return respond(HttpStatus.OK, "{\"access_token\":\"tok-1\",\"expires_in\":300}");
                }
                if (call.method().equals(HttpMethod.DELETE)) {
                    return respond(HttpStatus.NO_CONTENT, "");
                }
                if (call.path().endsWith("/active")) {
                    return respond(HttpStatus.OK, "{\"id\":\"active\",\"requiredActions\":[]}");
                }
                if (call.path().endsWith("/pending")) {
                    return respond(HttpStatus.OK, "{\"id\":\"pending\",\"requiredActions\":[\"UPDATE_PASSWORD\"]}");
                }
                return respond(HttpStatus.NOT_FOUND, "");
            });

            assertThatThrownBy(() -> adapter.deleteUnactivatedAccount("active"))
                    .isInstanceOf(IdentityProviderPort.AccountAlreadyActivatedException.class);
            adapter.deleteUnactivatedAccount("gone");
            assertThat(keycloak.callsTo(HttpMethod.DELETE, REALM_USERS)).isEmpty();

            adapter.deleteUnactivatedAccount("pending");
            assertThat(keycloak.callsTo(HttpMethod.DELETE, REALM_USERS))
                    .singleElement()
                    .satisfies(call -> assertThat(call.path()).isEqualTo(REALM_USERS + "/pending"));
        }
    }
}
