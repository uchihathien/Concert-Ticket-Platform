// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final TenantId ORG = TenantId.of(UUID.randomUUID());

    @Test
    void chi_luu_hash_khong_luu_token_tho() {
        Invitation.Issued issued = Invitation.issue(ORG, "a@example.com", Role.CHECKIN_STAFF, NOW);

        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.invitation().tokenHash()).isNotEqualTo(issued.rawToken());
        assertThat(Invitation.hash(issued.rawToken()))
                .isEqualTo(issued.invitation().tokenHash());
    }

    @Test
    void dung_mot_lan() {
        Invitation invitation =
                Invitation.issue(ORG, "a@example.com", Role.EVENT_MANAGER, NOW).invitation();
        invitation.accept(NOW);

        assertThatThrownBy(() -> invitation.accept(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("đã được sử dụng");
    }

    @Test
    void het_han_sau_7_ngay() {
        Invitation invitation =
                Invitation.issue(ORG, "a@example.com", Role.EVENT_MANAGER, NOW).invitation();
        Instant tooLate = NOW.plus(Invitation.LIFETIME).plusSeconds(1);

        assertThatThrownBy(() -> invitation.accept(tooLate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hết hạn");
    }

    @Test
    void email_duoc_chuan_hoa_ve_chu_thuong() {
        Invitation invitation = Invitation.issue(ORG, "  Nguoi.Dung@Example.COM ", Role.ORG_ADMIN, NOW)
                .invitation();
        assertThat(invitation.email()).isEqualTo("nguoi.dung@example.com");
    }
}
