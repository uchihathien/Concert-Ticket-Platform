// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link HttpMembershipLookup}: tra membership qua {@code GET /internal/memberships} của
 * identity-service, có cache ngắn.
 *
 * <p>Dùng {@link MockRestServiceServer} — không có mạng thật. Điều đang kiểm là <b>request đi ra
 * trông thế nào</b> (tham số, header bí mật) và <b>cache cư xử ra sao</b> (khoá theo phiên, không
 * cache lỗi, TTL).
 */
class HttpMembershipLookupTest {

    private static final String BASE = "http://identity-service:8090";
    private static final String USER_ID = "cccccccc-0000-4000-a000-000000000003";
    private static final String ORG = "aaaaaaaa-0000-4000-a000-000000000001";
    private static final String PAYLOAD =
            """
            {"userId":"%s","memberships":{"%s":"EVENT_MANAGER"},"superAdmin":false}
            """
                    .formatted(USER_ID, ORG);

    private MockRestServiceServer server;

    private HttpMembershipLookup lookup(String token, Duration ttl) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        return new HttpMembershipLookup(builder.build(), token, ttl);
    }

    private static MembershipLookup.Claims claims(String sid) {
        return new MembershipLookup.Claims(
                "kc-sub-1", "an@example.com", "Nguyễn Văn An", sid, Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Nested
    @DisplayName("Request đi ra")
    class OutgoingRequest {

        @Test
        @DisplayName("GET /internal/memberships mang đủ idpSubject, email, fullName, sid, issuedAt và bí mật nội bộ")
        void request_mang_du_tham_so() {
            HttpMembershipLookup sut = lookup("bi-mat", Duration.ofSeconds(60));
            server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/internal/memberships")))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(queryParam("idpSubject", "kc-sub-1"))
                    .andExpect(queryParam("email", "an@example.com"))
                    .andExpect(queryParam("sid", "sid-1"))
                    .andExpect(queryParam("issuedAt", "2026-10-01T00:00:00Z"))
                    .andExpect(header(InternalApiFilter.HEADER, "bi-mat"))
                    .andRespond(withSuccess(PAYLOAD, MediaType.APPLICATION_JSON));

            MembershipLookup.Principal principal = sut.resolve(claims("sid-1"));

            server.verify();
            assertThat(principal).isNotNull();
        }

        @Test
        @DisplayName("tham số null hoặc trống bị bỏ hẳn, không gửi '?email' rỗng")
        void tham_so_trong_bi_bo() {
            HttpMembershipLookup sut = lookup("bi-mat", Duration.ofSeconds(60));
            server.expect(once(), requestTo(BASE + "/internal/memberships?idpSubject=kc-sub-1"))
                    .andRespond(withSuccess(PAYLOAD, MediaType.APPLICATION_JSON));

            sut.resolve(new MembershipLookup.Claims("kc-sub-1", " ", null, "", null));

            server.verify();
        }

        @Test
        @DisplayName("không khai bí mật: không gửi header X-Internal-Token")
        void khong_bi_mat_khong_gui_header() {
            HttpMembershipLookup sut = lookup("", Duration.ofSeconds(60));
            server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE + "/internal/memberships")))
                    .andExpect(headerDoesNotExist(InternalApiFilter.HEADER))
                    .andRespond(withSuccess(PAYLOAD, MediaType.APPLICATION_JSON));

            sut.resolve(claims(null));

            server.verify();
        }
    }

    @Nested
    @DisplayName("Đọc phản hồi")
    class Response {

        @Test
        @DisplayName("payload được dịch đúng sang Principal: userId, vai trò theo tổ chức, cờ superadmin")
        void dich_payload_sang_principal() {
            HttpMembershipLookup sut = lookup("s", Duration.ofSeconds(60));
            server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                    .andRespond(withSuccess(
                            """
                            {"userId":"%s","memberships":{"%s":"ORG_OWNER"},"superAdmin":true}
                            """
                                    .formatted(USER_ID, ORG),
                            MediaType.APPLICATION_JSON));

            MembershipLookup.Principal principal = sut.resolve(claims("sid-1"));

            assertThat(principal.userId().value()).isEqualTo(UUID.fromString(USER_ID));
            assertThat(principal.memberships()).containsEntry(TenantId.parse(ORG), Role.ORG_OWNER);
            assertThat(principal.superAdmin()).isTrue();
        }

        @Test
        @DisplayName("memberships null: principal không có tổ chức nào, không NPE")
        void memberships_null() {
            HttpMembershipLookup sut = lookup("s", Duration.ofSeconds(60));
            server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                    .andRespond(withSuccess(
                            "{\"userId\":\"" + USER_ID + "\",\"superAdmin\":false}", MediaType.APPLICATION_JSON));

            assertThat(sut.resolve(claims("s")).memberships()).isEmpty();
        }

        @Test
        @DisplayName("payload thiếu userId: null — request sẽ là ẩn danh")
        void thieu_user_id_tra_null() {
            HttpMembershipLookup sut = lookup("s", Duration.ofSeconds(60));
            server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                    .andRespond(withSuccess("{\"superAdmin\":true}", MediaType.APPLICATION_JSON));

            assertThat(sut.resolve(claims("s"))).isNull();
        }

        @Test
        @DisplayName("claims null hoặc thiếu sub: null và không gọi identity")
        void claims_thieu_sub_khong_goi() {
            HttpMembershipLookup sut = lookup("s", Duration.ofSeconds(60));

            assertThat(sut.resolve(null)).isNull();
            assertThat(sut.resolve(new MembershipLookup.Claims(null, "a@b.c", "A")))
                    .isNull();

            server.verify(); // không có request nào được đăng ký, và không có request nào đi ra
        }
    }

    @Nested
    @DisplayName("Cache")
    class Cache {

        @Test
        @DisplayName("trong TTL: lần tra thứ hai của cùng phiên không gọi identity")
        void trong_ttl_dung_cache() {
            HttpMembershipLookup sut = lookup("s", Duration.ofMinutes(1));
            server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                    .andRespond(withSuccess(PAYLOAD, MediaType.APPLICATION_JSON));

            MembershipLookup.Principal first = sut.resolve(claims("sid-1"));
            MembershipLookup.Principal second = sut.resolve(claims("sid-1"));

            server.verify();
            assertThat(second).isSameAs(first);
        }

        @Test
        @DisplayName("khoá cache gồm phiên: phiên khác của cùng người dùng phải hỏi lại identity")
        void phien_khac_hoi_lai() {
            HttpMembershipLookup sut = lookup("s", Duration.ofMinutes(1));
            server.expect(times(2), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                    .andRespond(withSuccess(PAYLOAD, MediaType.APPLICATION_JSON));

            sut.resolve(claims("sid-1"));
            sut.resolve(claims("sid-2"));

            server.verify();
        }

        @Test
        @DisplayName("lỗi từ identity không được cache: identity hồi phục thì request kế tiếp thấy ngay")
        void loi_khong_duoc_cache() {
            HttpMembershipLookup sut = lookup("s", Duration.ofMinutes(1));
            server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                    .andRespond(withServerError());
            server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                    .andRespond(withSuccess(PAYLOAD, MediaType.APPLICATION_JSON));

            MembershipLookup.Principal duringOutage = sut.resolve(claims("sid-1"));
            MembershipLookup.Principal afterRecovery = sut.resolve(claims("sid-1"));

            server.verify();
            assertThat(duringOutage).isNull();
            assertThat(afterRecovery).isNotNull();
        }

        @Test
        @DisplayName("TTL bằng 0: mọi lần tra đều hỏi lại identity — hết hạn là hết hạn")
        void ttl_khong_luon_hoi_lai() {
            HttpMembershipLookup sut = lookup("s", Duration.ZERO);
            server.expect(times(2), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                    .andRespond(withSuccess(PAYLOAD, MediaType.APPLICATION_JSON));

            sut.resolve(claims("sid-1"));
            sut.resolve(claims("sid-1"));

            server.verify();
        }
    }
}
