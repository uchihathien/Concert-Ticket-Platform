// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import com.nexaticket.aichatbox.domain.port.IdentityLookupPort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang identity-service, chỉ để lấy tên người trực.
 *
 * <p>Gọi {@code GET /internal/users/{id}} — Open Host Service của identity, cùng endpoint mà
 * notification-service dùng để lấy địa chỉ nhận thư.
 */
@Component
public class IdentityHttpAdapter implements IdentityLookupPort {

    private static final Logger log = LoggerFactory.getLogger(IdentityHttpAdapter.class);

    /**
     * Nhớ tên đã tra, không hết hạn.
     *
     * <h3>Vì sao một Map trần là đủ, và vì sao nó không phình</h3>
     *
     * Khoá chỉ là id của <b>người trực</b> — những người bấm "nhận phiếu". Đó là một nhóm nhân sự,
     * hàng chục người, không phải tập khách hàng. Mỗi lần mở hàng đợi 50 phiếu mà không nhớ gì thì
     * là 50 lời gọi HTTP nằm trong một request người trực đang chờ.
     *
     * <p>Không hết hạn là một đánh đổi có ý thức: người đổi tên sẽ hiện tên cũ tới lần khởi động
     * lại service. Cái giá đó nhỏ hơn hẳn việc dựng một tầng cache có vòng đời cho một chuỗi dùng
     * để hiện lên màn hình.
     */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    // Cache riêng cho contact. Không gộp vào `names` vì một lần tra ra tên nhưng không có email vẫn
    // là một kết quả hợp lệ, và gộp lại thì không phân biệt được "chưa tra" với "tra rồi, không có".
    private final Map<UUID, IdentityLookupPort.Contact> contacts = new ConcurrentHashMap<>();

    private final RestClient client;

    public IdentityHttpAdapter(@Qualifier("identityClient") RestClient client) {
        this.client = client;
    }

    @Override
    public Optional<IdentityLookupPort.Contact> contactOf(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        IdentityLookupPort.Contact cached = contacts.get(userId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            UserContactResponse response =
                    client.get().uri("/internal/users/{id}", userId).retrieve().body(UserContactResponse.class);
            if (response == null) {
                return Optional.empty();
            }
            IdentityLookupPort.Contact contact =
                    new IdentityLookupPort.Contact(response.displayName(), response.email());
            if (contact.fullName() == null && contact.email() == null) {
                return Optional.empty();
            }
            contacts.put(userId, contact);
            return Optional.of(contact);
        } catch (RuntimeException e) {
            log.warn("Không tra được thông tin người dùng {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> displayNameOf(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        String cached = names.get(userId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            UserContactResponse response =
                    client.get().uri("/internal/users/{id}", userId).retrieve().body(UserContactResponse.class);
            String name = response == null ? null : response.displayName();
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            names.put(userId, name);
            return Optional.of(name);
        } catch (RuntimeException e) {
            // KHÔNG ném: xem IdentityLookupPort. Mức WARN chứ không ERROR — bàn hỗ trợ vẫn chạy
            // đúng, chỉ thiếu một cái tên.
            log.warn("Không tra được tên người dùng {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Hợp đồng với {@code InternalMembershipController} của identity-service.
     *
     * <p>Lấy {@code fullName}, và lùi về phần trước {@code @} của email khi tên còn trống: một phiếu
     * ghi "thanh.le" vẫn nói cho người đọc biết ai đang cầm nó, còn "nhân viên hỗ trợ" thì không.
     * Không hiện email đầy đủ — nó là dữ liệu cá nhân và không cần thiết để nhận ra đồng nghiệp.
     */
    private record UserContactResponse(String userId, String email, String fullName) {

        String displayName() {
            if (fullName != null && !fullName.isBlank()) {
                return fullName.strip();
            }
            if (email == null || email.isBlank()) {
                return null;
            }
            int at = email.indexOf('@');
            return at > 0 ? email.substring(0, at) : email;
        }
    }
}
