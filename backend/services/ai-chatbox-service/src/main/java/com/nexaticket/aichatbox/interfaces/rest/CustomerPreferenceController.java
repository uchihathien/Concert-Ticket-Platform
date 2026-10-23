// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import com.nexaticket.aichatbox.application.preference.CustomerPreferenceUseCase;
import com.nexaticket.platform.security.tenant.TenantContext;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khách xem và xoá sở thích trợ lý đã ghi nhớ — không cần nhờ trợ lý.
 *
 * <p>Xoá qua chat ({@code forgetCustomerPreferences}) phụ thuộc vào việc mô hình hiểu đúng ý khách.
 * Quyền xoá dữ liệu của chính mình không được phụ thuộc vào điều đó, nên có đường REST tất định này.
 * Chỉ thao tác trên dòng của người gọi (userId từ JWT), không nhận userId từ ngoài.
 */
@RestController
@RequestMapping("/v1/chat/agent/preferences")
public class CustomerPreferenceController {

    private final CustomerPreferenceUseCase preferences;

    public CustomerPreferenceController(CustomerPreferenceUseCase preferences) {
        this.preferences = preferences;
    }

    /** 200 kèm sở thích; 204 khi chưa lưu gì. */
    @GetMapping
    public ResponseEntity<CustomerPreferenceUseCase.View> mine() {
        UUID userId = TenantContext.requireAuthenticated().userId().value();
        return preferences.view(userId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent()
                .build());
    }

    /** 204, idempotent: chưa có gì để xoá thì cũng 204. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget() {
        UUID userId = TenantContext.requireAuthenticated().userId().value();
        preferences.forget(userId);
    }
}
