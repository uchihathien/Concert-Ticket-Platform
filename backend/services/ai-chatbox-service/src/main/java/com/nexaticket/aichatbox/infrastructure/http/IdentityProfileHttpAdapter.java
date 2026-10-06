// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import com.nexaticket.aichatbox.domain.model.CustomerProfile;
import com.nexaticket.aichatbox.domain.port.CustomerProfilePort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Hồ sơ của chính khách, qua {@code GET /v1/me} của identity-service với token của khách.
 *
 * <p>Tách khỏi {@link IdentityHttpAdapter} dù dùng chung client HTTP: lớp kia đi đường nội bộ và
 * có cache tên không hết hạn — hai thứ đều SAI cho hồ sơ khách. Hồ sơ không được cache: khách vừa
 * sửa tên rồi hỏi "tên trên tài khoản mình là gì" phải nhận bản mới.
 */
@Component
public class IdentityProfileHttpAdapter implements CustomerProfilePort {

    private static final String SERVICE = "identity-service";

    private final RestClient client;

    public IdentityProfileHttpAdapter(@Qualifier("identityClient") RestClient client) {
        this.client = client;
    }

    @Override
    public CustomerProfile currentProfile(String callerAccessToken) {
        try {
            ProfileResponse response = client.get()
                    .uri("/v1/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerAccessToken)
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new RemoteCallException(SERVICE, "Identity trả " + status, null);
                        }
                        return clientResponse.bodyTo(ProfileResponse.class);
                    });
            if (response == null) {
                throw new RemoteCallException(SERVICE, "Identity trả body rỗng", null);
            }
            return new CustomerProfile(response.fullName(), response.email(), response.phone());
        } catch (RemoteCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RemoteCallException(SERVICE, "Không gọi được identity-service", e);
        }
    }

    /**
     * Hợp đồng với {@code ProfileView} của identity. Cố ý <b>không</b> khai {@code superAdmin} và
     * {@code organizations}: chúng không trả lời câu hỏi nào của khách, và thứ không đi vào tiến
     * trình thì không lọt vào prompt.
     */
    private record ProfileResponse(String id, String email, String fullName, String phone) {}
}
