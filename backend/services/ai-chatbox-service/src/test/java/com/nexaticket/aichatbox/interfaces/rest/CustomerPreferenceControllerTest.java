// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.preference.CustomerPreferenceUseCase;
import com.nexaticket.aichatbox.application.preference.CustomerPreferenceUseCase.Update;
import com.nexaticket.aichatbox.support.InMemoryCustomerPreferences;
import com.nexaticket.platform.security.tenant.ChatTenantFixture;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Khách xem và xoá sở thích của CHÍNH mình — userId lấy từ phiên đăng nhập. */
class CustomerPreferenceControllerTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();
    private final InMemoryCustomerPreferences rows = new InMemoryCustomerPreferences();
    private final CustomerPreferenceUseCase prefs = new CustomerPreferenceUseCase(rows, Clock.systemUTC());
    private final CustomerPreferenceController controller = new CustomerPreferenceController(prefs);

    @BeforeEach
    void login() {
        ChatTenantFixture.customer(userId);
    }

    @AfterEach
    void logout() {
        ChatTenantFixture.clear();
    }

    @Test
    void chua_luu_gi_thi_204() {
        assertThat(controller.mine().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void doc_dung_dong_cua_minh() {
        prefs.remember(userId, new Update(List.of("nhac-song"), "Huế", null, null));
        prefs.remember(otherUser, new Update(List.of("the-thao"), null, null, null));

        var body = controller.mine().getBody();
        assertThat(body).isNotNull();
        assertThat(body.categories()).containsExactly("nhac-song");
        assertThat(body.cities()).containsExactly("Huế");
    }

    @Test
    void xoa_chi_xoa_dong_cua_minh_va_idempotent() {
        prefs.remember(userId, new Update(List.of("nhac-song"), null, null, null));
        prefs.remember(otherUser, new Update(List.of("the-thao"), null, null, null));

        controller.forget();
        controller.forget();

        assertThat(rows.rows).containsOnlyKeys(otherUser);
    }
}
