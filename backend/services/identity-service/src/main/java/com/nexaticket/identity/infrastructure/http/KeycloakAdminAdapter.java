// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.http;

import com.nexaticket.identity.domain.port.IdentityProviderPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * Anti-Corruption Layer sang Admin API của Keycloak.
 *
 * <p>Không kiểu nào của Keycloak đi quá lớp này, và lớp này chỉ gọi đúng một endpoint:
 * {@code execute-actions-email} với hành động {@code UPDATE_PASSWORD}. Nó nhờ Keycloak gửi thư —
 * nó <b>không</b> đặt được mật khẩu, không đọc được thông tin đăng nhập, và không mạo danh được ai.
 *
 * <p>Chưa cấu hình thì bean vẫn tồn tại và <b>ném lỗi có nội dung</b> khi bị gọi, thay vì biến mất
 * khỏi context. Bean biến mất nghĩa là cả identity-service không khởi động được vì thiếu một tính
 * năng phụ — và ở dev thì thiếu bí mật là trạng thái bình thường.
 */
@Component
public class KeycloakAdminAdapter implements IdentityProviderPort {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminAdapter.class);

    /**
     * Đổi lại token sớm hơn hạn 30 giây.
     *
     * <p>Không có biên này thì một token còn đúng một giây vẫn được coi là dùng được, và Keycloak
     * từ chối nó ngay sau đó — một lỗi hiếm, khó tái hiện, và luôn xảy ra đúng lúc có người đang
     * bấm nút.
     */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final KeycloakAdminProperties properties;
    private final RestClient client;

    /** Token của service account, dùng lại giữa các lời gọi. Ghi/đọc từ nhiều luồng nên volatile. */
    private volatile CachedToken cachedToken;

    private record CachedToken(String value, Instant expiresAt) {}

    public KeycloakAdminAdapter(KeycloakAdminProperties properties, RestClient.Builder builder) {
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        // Builder ĐƯỢC TIÊM: lời gọi Admin API xuất hiện như một span con trong trace, nên khi
        // màn hình quản trị chậm thì thấy ngay là chậm ở Keycloak hay ở ta.
        this.client = builder.baseUrl(properties.baseUrl() == null ? "http://localhost:8081" : properties.baseUrl())
                .requestFactory(factory)
                .build();

        if (!properties.isConfigured()) {
            log.warn("nexaticket.identity.keycloak.client-secret chưa khai: chức năng gửi thư đặt lại "
                    + "mật khẩu từ màn hình quản trị sẽ trả lỗi. Người dùng vẫn tự đặt lại được qua "
                    + "liên kết 'Quên mật khẩu?' trên trang đăng nhập của Keycloak.");
        }
    }

    @Override
    public void sendPasswordResetEmail(String idpSubject, String clientId, String redirectUri) {
        if (!properties.isConfigured()) {
            throw new IdentityProviderUnavailableException(
                    "Chưa cấu hình Keycloak Admin API (nexaticket.identity.keycloak.*)", null);
        }

        String resolvedClient = clientId != null ? clientId : properties.defaultResetClientId();
        String resolvedRedirect = redirectUri != null ? redirectUri : properties.defaultResetRedirectUri();

        try {
            client.put()
                    .uri(uri -> executeActionsUri(uri, idpSubject, resolvedClient, resolvedRedirect))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    // Đúng MỘT hành động. Thêm VERIFY_EMAIL vào đây trông có vẻ tiện, nhưng nó biến
                    // một thao tác "đặt lại mật khẩu" thành một thao tác cũng đổi cả trạng thái xác
                    // minh email — và người bấm nút không biết điều đó.
                    .body(List.of("UPDATE_PASSWORD"))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 404) {
                            throw new IdentityProviderUnavailableException(
                                    "Keycloak không có tài khoản " + idpSubject, null);
                        }
                        if (!status.is2xxSuccessful()) {
                            // Kèm nguyên văn lỗi của Keycloak. Không có nó, nguyên nhân thường gặp
                            // nhất — "Invalid redirect uri", tức là `redirect_uri` chưa nằm trong
                            // danh sách đã khai của client — hiện ra chỉ là một con số 400, và
                            // người vận hành không có gì để lần theo.
                            throw new IdentityProviderUnavailableException(
                                    "Keycloak trả " + status + " khi gửi thư đặt lại mật khẩu: " + errorBody(response),
                                    null);
                        }
                        return null;
                    });
            log.info("Đã nhờ Keycloak gửi thư đặt lại mật khẩu cho {}", idpSubject);
        } catch (IdentityProviderUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IdentityProviderUnavailableException("Không gọi được Keycloak Admin API", e);
        }
    }

    private java.net.URI executeActionsUri(UriBuilder uri, String idpSubject, String clientId, String redirectUri) {
        uri.path("/admin/realms/{realm}/users/{userId}/execute-actions-email");
        // Hai tham số này đi cùng nhau hoặc không đi: Keycloak từ chối `redirect_uri` khi thiếu
        // `client_id`, và bỏ qua `client_id` khi thiếu `redirect_uri`. Gửi lẻ một cái là gửi một
        // tham số không có tác dụng, rồi tự hỏi vì sao thư dẫn về sai chỗ.
        if (hasText(clientId) && hasText(redirectUri)) {
            uri.queryParam("client_id", clientId);
            uri.queryParam("redirect_uri", redirectUri);
        }
        return uri.build(properties.realm(), idpSubject);
    }

    /**
     * Token của service account, lấy bằng {@code client_credentials}.
     *
     * <p>Có cache: mỗi lời gọi Admin API mà xin token mới là hai lần đi-về thay vì một, và token
     * của Keycloak sống hàng phút. Không đồng bộ hoá quanh việc làm mới — hai luồng cùng làm mới
     * thì tệ nhất là xin thừa một token, rẻ hơn nhiều so với việc khoá một tài nguyên dùng chung
     * trên đường có gọi mạng.
     */
    private String accessToken() {
        CachedToken current = cachedToken;
        Instant now = Instant.now();
        if (current != null && current.expiresAt().isAfter(now)) {
            return current.value();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        TokenResponse response = client.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", properties.realm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IdentityProviderUnavailableException("Keycloak không cấp token cho service account", null);
        }

        cachedToken = new CachedToken(
                response.accessToken(), now.plusSeconds(response.expiresIn()).minus(EXPIRY_MARGIN));
        return response.accessToken();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Đọc thân lỗi của Keycloak; đọc không ra thì trả chuỗi rỗng chứ không che mất lỗi gốc. */
    private static String errorBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            String body = response.bodyTo(String.class);
            return body == null ? "" : body;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Chỉ hai trường được dùng; phần còn lại của response bị bỏ qua. */
    private record TokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") long expiresIn) {}
}
