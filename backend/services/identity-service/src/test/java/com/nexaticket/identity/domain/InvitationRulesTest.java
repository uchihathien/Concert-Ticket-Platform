// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Lời mời: những biên mà {@code InvitationTest} chưa kiểm — đầu vào bị từ chối, mốc hết hạn chính
 * xác, và tính ngẫu nhiên của token.
 */
class InvitationRulesTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final TenantId ORG = TenantId.of(UUID.fromString("11111111-1111-4111-a111-111111111111"));

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"khong-co-a-cong", "   "})
    @DisplayName("email không có @ bị từ chối")
    void email_khong_hop_le(String email) {
        assertThatThrownBy(() -> Invitation.issue(ORG, email, Role.ORG_ADMIN, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"SUPER_ADMIN", "CUSTOMER"})
    @DisplayName("không mời được vào vai trò ngoài phạm vi tổ chức")
    void vai_tro_ngoai_pham_vi(Role role) {
        assertThatThrownBy(() -> Invitation.issue(ORG, "a@example.com", role, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("hết hạn đúng sau 7 ngày: tại đúng mốc vẫn nhận được")
    void tai_dung_moc_het_han() {
        Invitation invitation =
                Invitation.issue(ORG, "a@example.com", Role.ORG_ADMIN, NOW).invitation();

        assertThat(invitation.expiresAt()).isEqualTo(NOW.plus(Invitation.LIFETIME));
        invitation.accept(invitation.expiresAt());
        assertThat(invitation.acceptedAt()).isEqualTo(invitation.expiresAt());
    }

    @Test
    @DisplayName("lời mời đã dùng dựng lại từ database vẫn không dùng lại được")
    void dung_lai_sau_khi_doc_tu_database() {
        Invitation rehydrated = Invitation.rehydrate(
                UUID.randomUUID(),
                ORG,
                "a@example.com",
                Role.EVENT_MANAGER,
                "hash",
                NOW.plus(Invitation.LIFETIME),
                NOW.minusSeconds(60));

        assertThatThrownBy(() -> rehydrated.accept(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("đã được sử dụng");
    }

    @Test
    @DisplayName("lời mời vừa đã dùng vừa hết hạn báo 'đã dùng' — người dùng cần biết đúng lý do")
    void da_dung_uu_tien_hon_het_han() {
        Invitation rehydrated = Invitation.rehydrate(
                UUID.randomUUID(), ORG, "a@example.com", Role.EVENT_MANAGER, "hash", NOW, NOW.minusSeconds(60));

        assertThatThrownBy(() -> rehydrated.accept(NOW.plusSeconds(3600))).hasMessageContaining("đã được sử dụng");
    }

    @Test
    @DisplayName("mỗi lời mời một token khác nhau, đủ dài để không đoán được")
    void token_ngau_nhien() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            tokens.add(
                    Invitation.issue(ORG, "a@example.com", Role.ORG_ADMIN, NOW).rawToken());
        }

        assertThat(tokens).hasSize(200);
        // 32 byte ngẫu nhiên, base64url không đệm: 43 ký tự, không có ký tự phải mã hoá trên URL.
        assertThat(tokens).allSatisfy(token -> assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+"));
    }

    @Test
    @DisplayName("hash cố định với cùng token và khác nhau với token khác")
    void hash_xac_dinh() {
        assertThat(Invitation.hash("abc")).isEqualTo(Invitation.hash("abc"));
        assertThat(Invitation.hash("abc")).isNotEqualTo(Invitation.hash("abd"));
    }
}
