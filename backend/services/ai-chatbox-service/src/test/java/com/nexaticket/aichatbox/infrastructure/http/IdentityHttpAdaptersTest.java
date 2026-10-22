// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.domain.model.CustomerProfile;
import com.nexaticket.aichatbox.domain.port.IdentityLookupPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.support.StubHttpServer;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Các adapter sang identity-service và nguồn token của người gọi. */
@DisplayName("Adapter identity")
class IdentityHttpAdaptersTest {

    private static final String TOKEN = "jwt-cua-khach.abc.def";

    private final StubHttpServer server = new StubHttpServer();
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        server.close();
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("IdentityHttpAdapter (tra tên người trực, có cache)")
    class Lookup {

        private IdentityHttpAdapter adapterAt(String baseUrl) {
            return new IdentityHttpAdapter(HttpTestClients.client(baseUrl));
        }

        private IdentityHttpAdapter adapter() {
            return adapterAt(server.baseUrl());
        }

        @Test
        @DisplayName("id null thì rỗng và KHÔNG gọi mạng")
        void id_null_khong_goi_mang() {
            IdentityHttpAdapter adapter = adapter();

            assertThat(adapter.displayNameOf(null)).isEmpty();
            assertThat(adapter.contactOf(null)).isEmpty();
            assertThat(server.received()).isEmpty();
        }

        @Test
        @DisplayName("tra tên gọi /internal/users/{id}, ưu tiên fullName đã cắt khoảng trắng")
        void tra_ten() {
            server.respond(
                    200, "{\"userId\":\"" + userId + "\",\"email\":\"thanh.le@x.vn\",\"fullName\":\"  Lê Thanh  \"}");

            assertThat(adapter().displayNameOf(userId)).contains("Lê Thanh");
            assertThat(server.only().path()).isEqualTo("/internal/users/" + userId);
        }

        @Test
        @DisplayName("fullName trống thì lùi về phần trước @ của email — không hiện email đầy đủ")
        void lui_ve_email() {
            server.respond(200, "{\"email\":\"thanh.le@congty.vn\",\"fullName\":\" \"}");

            assertThat(adapter().displayNameOf(userId)).contains("thanh.le");
        }

        @Test
        @DisplayName("email không có @ hoặc bắt đầu bằng @ thì dùng nguyên chuỗi")
        void email_la() {
            server.respond(200, "{\"email\":\"khong-co-a-cong\"}");
            assertThat(adapter().displayNameOf(userId)).contains("khong-co-a-cong");

            try (StubHttpServer local = new StubHttpServer()) {
                local.respond(200, "{\"email\":\"@x.vn\"}");
                assertThat(adapterAt(local.baseUrl()).displayNameOf(userId)).contains("@x.vn");
            }
        }

        @Test
        @DisplayName("không có cả tên lẫn email thì rỗng, và KHÔNG được nhớ — lần sau hỏi lại")
        void khong_co_gi_thi_khong_cache() {
            server.respond(200, "{}");
            IdentityHttpAdapter adapter = adapter();

            assertThat(adapter.displayNameOf(userId)).isEmpty();
            assertThat(adapter.displayNameOf(userId)).isEmpty();

            assertThat(server.received()).hasSize(2);
        }

        @Test
        @DisplayName("tên đã tra được nhớ — một người trực chỉ tốn một lời gọi HTTP")
        void ten_duoc_cache() {
            server.respond(200, "{\"fullName\":\"Lê Thanh\"}");
            IdentityHttpAdapter adapter = adapter();

            adapter.displayNameOf(userId);
            adapter.displayNameOf(userId);
            adapter.displayNameOf(userId);

            assertThat(server.received()).hasSize(1);
        }

        @Test
        @DisplayName("lỗi 404, 500 hoặc mạng đứt thì rỗng, không ném, không nhớ lỗi")
        void loi_thi_rong_va_khong_cache() {
            server.respondWith(r -> server.received().size() == 1
                    ? new StubHttpServer.Reply(500, "{}")
                    : new StubHttpServer.Reply(200, "{\"fullName\":\"Lê Thanh\"}"));
            IdentityHttpAdapter adapter = adapter();

            assertThat(adapter.displayNameOf(userId)).isEmpty();
            // Identity chập chờn rồi hồi phục: lần sau phải tra lại được, không bị kẹt ở "không biết".
            assertThat(adapter.displayNameOf(userId)).contains("Lê Thanh");

            assertThat(adapterAt(StubHttpServer.deadBaseUrl()).displayNameOf(userId))
                    .isEmpty();
        }

        @Test
        @DisplayName("body rỗng thì rỗng")
        void body_rong() {
            server.respond(200, "");

            assertThat(adapter().displayNameOf(userId)).isEmpty();
            assertThat(adapter().contactOf(userId)).isEmpty();
        }

        @Test
        @DisplayName("contactOf trả cả tên lẫn email đầy đủ, và được nhớ riêng với cache tên")
        void contact_cache_rieng() {
            server.respond(200, "{\"email\":\"thanh.le@x.vn\",\"fullName\":\"Lê Thanh\"}");
            IdentityHttpAdapter adapter = adapter();

            Optional<IdentityLookupPort.Contact> first = adapter.contactOf(userId);
            adapter.contactOf(userId);

            assertThat(first).contains(new IdentityLookupPort.Contact("Lê Thanh", "thanh.le@x.vn"));
            assertThat(server.received()).hasSize(1);
            // Cache tên là cache KHÁC: tra tên sau khi tra contact vẫn phải đi mạng một lần.
            adapter.displayNameOf(userId);
            assertThat(server.received()).hasSize(2);
        }

        @Test
        @DisplayName("contactOf chỉ có email thì tên lùi về phần trước @, email giữ nguyên")
        void contact_chi_co_email() {
            server.respond(200, "{\"email\":\"thanh.le@x.vn\"}");

            assertThat(adapter().contactOf(userId))
                    .contains(new IdentityLookupPort.Contact("thanh.le", "thanh.le@x.vn"));
        }

        @Test
        @DisplayName("contactOf lỗi thì rỗng, không ném")
        void contact_loi() {
            server.respond(503, "{}");

            assertThat(adapter().contactOf(userId)).isEmpty();
            assertThat(adapterAt(StubHttpServer.deadBaseUrl()).contactOf(userId))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("IdentityProfileHttpAdapter (hồ sơ của chính khách, không cache)")
    class Profile {

        private IdentityProfileHttpAdapter adapterAt(String baseUrl) {
            return new IdentityProfileHttpAdapter(HttpTestClients.client(baseUrl));
        }

        @Test
        @DisplayName("gọi /v1/me bằng token của khách và bỏ qua quyền quản trị, tổ chức")
        void goi_v1_me() {
            server.respond(
                    200,
                    "{\"id\":\"" + userId
                            + "\",\"email\":\"a@x.vn\",\"fullName\":\"Lữ Đình Thiện\",\"phone\":\"0912345678\","
                            + "\"superAdmin\":true,\"organizations\":[{\"id\":\"1\"}]}");

            CustomerProfile profile = adapterAt(server.baseUrl()).currentProfile(TOKEN);

            StubHttpServer.Received sent = server.only();
            assertThat(sent.path()).isEqualTo("/v1/me");
            assertThat(sent.header("authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(profile).isEqualTo(new CustomerProfile("Lữ Đình Thiện", "a@x.vn", "0912345678"));
        }

        @Test
        @DisplayName("không nhớ gì: hai lần gọi là hai request — khách vừa sửa tên phải thấy bản mới")
        void khong_cache() {
            server.respond(200, "{\"fullName\":\"A\"}");
            IdentityProfileHttpAdapter adapter = adapterAt(server.baseUrl());

            adapter.currentProfile(TOKEN);
            adapter.currentProfile(TOKEN);

            assertThat(server.received()).hasSize(2);
        }

        @Test
        @DisplayName("401, 5xx, body rỗng, mạng đứt đều là RemoteCallException, không lộ token")
        void loi() {
            for (int status : new int[] {401, 404, 500}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(status, "{}");

                    assertThatThrownBy(() -> adapterAt(local.baseUrl()).currentProfile(TOKEN))
                            .as("HTTP %d", status)
                            .isInstanceOfSatisfying(RemoteCallException.class, e -> {
                                assertThat(e.service()).isEqualTo("identity-service");
                                assertThat(e.getMessage()).doesNotContain(TOKEN);
                            });
                }
            }
            server.respond(200, "");
            assertThatThrownBy(() -> adapterAt(server.baseUrl()).currentProfile(TOKEN))
                    .isInstanceOf(RemoteCallException.class)
                    .hasMessageContaining("rỗng");
            assertThatThrownBy(() -> adapterAt(StubHttpServer.deadBaseUrl()).currentProfile(TOKEN))
                    .isInstanceOf(RemoteCallException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("SecurityContextCallerCredentials")
    class Credentials {

        private final SecurityContextCallerCredentials credentials = new SecurityContextCallerCredentials();

        @Test
        @DisplayName("lấy đúng giá trị access token từ JWT của request đang chạy")
        void lay_token_tu_jwt() {
            Jwt jwt =
                    Jwt.withTokenValue(TOKEN).header("alg", "none").subject("u").build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

            assertThat(credentials.currentAccessToken()).isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("chạy ngoài request đã xác thực thì ném IllegalStateException thay vì gọi bằng quyền của service")
        void khong_co_jwt_thi_nem() {
            SecurityContextHolder.clearContext();
            assertThatThrownBy(credentials::currentAccessToken)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT");

            // Một kiểu xác thực KHÁC (không phải JWT) cũng không được coi là có token.
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", "ROLE_USER"));
            assertThatThrownBy(credentials::currentAccessToken).isInstanceOf(IllegalStateException.class);
        }
    }
}
