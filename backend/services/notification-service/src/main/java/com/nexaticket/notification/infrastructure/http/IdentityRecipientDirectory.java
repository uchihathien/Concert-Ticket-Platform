// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.notification.infrastructure.http;

import com.nexaticket.notification.domain.port.RecipientDirectory;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Anti-Corruption Layer sang identity-service. */
@Component
public class IdentityRecipientDirectory implements RecipientDirectory {

    private final RestClient client;

    public IdentityRecipientDirectory(
            @Value("${nexaticket.notification.identity-url:http://localhost:8090}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client =
                RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Recipient lookup(UUID userId) {
        try {
            ContactResponse response = client.get()
                    .uri("/internal/users/{id}/contact", userId)
                    .retrieve()
                    .body(ContactResponse.class);
            if (response == null || response.email() == null) {
                throw new DirectoryUnavailableException("Identity không trả email cho user " + userId, null);
            }
            return new Recipient(response.email(), response.fullName());
        } catch (DirectoryUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DirectoryUnavailableException("Không tra được người nhận " + userId, e);
        }
    }

    record ContactResponse(UUID userId, String email, String fullName) {}
}
