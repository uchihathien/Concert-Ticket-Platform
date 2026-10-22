// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import static com.nexaticket.identity.application.support.Fixtures.CLOCK;
import static com.nexaticket.identity.application.support.Fixtures.ORG;
import static com.nexaticket.identity.application.support.Fixtures.errorCode;
import static com.nexaticket.identity.application.support.Fixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.Slug;
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tạo tổ chức — cửa vào duy nhất của luồng onboarding, chỉ superadmin (ADR-1010).
 *
 * <p>Ba thứ phải đi cùng nhau trong một lệnh: tổ chức, lời mời chủ sở hữu và sự kiện outbox. Thiếu
 * lời mời thì tổ chức không ai vào được; thiếu sự kiện thì không ai nhận được thư mời.
 */
class CreateOrganizationHandlerTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final InvitationRepository invitations = mock(InvitationRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final CreateOrganizationHandler handler =
            new CreateOrganizationHandler(organizations, invitations, outbox, audit, CLOCK);

    @BeforeEach
    void superadmin() {
        TenantContextFixture.superAdmin(newUser());
    }

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    private static CreateOrganization command(String name, String slug) {
        return new CreateOrganization(name, slug, "Chu.So.Huu@Example.com", null);
    }

    @Nested
    @DisplayName("uỷ quyền")
    class UyQuyen {

        @Test
        @DisplayName("chủ sở hữu của một tổ chức khác không tạo được tổ chức")
        void nguoi_cua_to_chuc_khong_tao_duoc() {
            TenantContextFixture.member(newUser(), ORG, Role.ORG_OWNER);

            assertThatThrownBy(() -> handler.handle(command("Nhà hát", null)))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(organizations, invitations, outbox, audit);
        }

        @Test
        @DisplayName("chưa đăng nhập thì 401, không phải 403")
        void chua_dang_nhap() {
            TenantContextFixture.clear();

            assertThatThrownBy(() -> handler.handle(command("Nhà hát", null)))
                    .is(errorCode(ErrorCode.Common.UNAUTHENTICATED));
        }
    }

    @Test
    @DisplayName("tạo tổ chức, lời mời ORG_OWNER, nhật ký và sự kiện outbox trong cùng một lệnh")
    void tao_day_du() {
        CreateOrganizationHandler.Result result = handler.handle(command("Nhà hát Lớn Hà Nội", null));

        ArgumentCaptor<Organization> org = ArgumentCaptor.forClass(Organization.class);
        verify(organizations).save(org.capture());
        assertThat(org.getValue().slug().value()).isEqualTo("nha-hat-lon-ha-noi");
        assertThat(org.getValue().members())
                .as("chủ sở hữu vào bằng lời mời, không được gắn sẵn")
                .isEmpty();

        ArgumentCaptor<Invitation> invitation = ArgumentCaptor.forClass(Invitation.class);
        verify(invitations).save(invitation.capture());
        assertThat(invitation.getValue().role()).isEqualTo(Role.ORG_OWNER);
        assertThat(invitation.getValue().email()).isEqualTo("chu.so.huu@example.com");
        assertThat(invitation.getValue().organizationId())
                .isEqualTo(org.getValue().id());
        // Token thô trả về đúng một lần và khớp hash đã lưu — không thì thư mời là một link chết.
        assertThat(Invitation.hash(result.invitationToken()))
                .isEqualTo(invitation.getValue().tokenHash());

        verify(audit)
                .record(
                        eq("ORGANIZATION_CREATED"),
                        eq("organization"),
                        eq(org.getValue().id().value()),
                        isNull(),
                        any());
        verify(outbox)
                .append(
                        eq(CreateOrganizationHandler.EXCHANGE),
                        eq("Organization"),
                        eq(org.getValue().id().value()),
                        eq("organization.created"),
                        any());
        assertThat(result.organization().slug()).isEqualTo("nha-hat-lon-ha-noi");
        assertThat(result.organization().status()).isEqualTo("ACTIVE");
    }

    @Nested
    @DisplayName("slug")
    class SlugTrung {

        @Test
        @DisplayName("slug sinh từ tên mà trùng thì tự thêm hậu tố nhỏ nhất còn trống")
        void tu_them_hau_to() {
            when(organizations.slugExists(new Slug("nha-hat"))).thenReturn(true);
            when(organizations.slugExists(new Slug("nha-hat-2"))).thenReturn(true);

            assertThat(handler.handle(command("Nhà hát", null)).organization().slug())
                    .isEqualTo("nha-hat-3");
        }

        @Test
        @DisplayName("slug gõ tay mà trùng thì báo lỗi, không tự đổi thành slug khác người ta không chọn")
        void slug_go_tay_trung() {
            when(organizations.slugExists(new Slug("nha-hat"))).thenReturn(true);

            assertThatThrownBy(() -> handler.handle(command("Nhà hát", "nha-hat")))
                    .is(errorCode(IdentityErrorCode.SLUG_ALREADY_TAKEN));
            verify(organizations, never()).save(any());
            verify(invitations, never()).save(any());
            verify(outbox, never()).append(anyString(), anyString(), any(), anyString(), any());
        }

        @Test
        @DisplayName("hết 50 lần thử hậu tố thì báo trùng slug, không lặp vô hạn")
        void het_lan_thu() {
            when(organizations.slugExists(any())).thenReturn(true);

            assertThatThrownBy(() -> handler.handle(command("Nhà hát", null)))
                    .is(errorCode(IdentityErrorCode.SLUG_ALREADY_TAKEN));
        }

        @Test
        @DisplayName("tên dài 64 ký tự trùng slug vẫn ra lỗi nghiệp vụ, không phải IllegalArgumentException (500)")
        void ten_dai_trung_slug() {
            // Slug.from cắt về 64 ký tự; withSuffix nối thêm "-2" thành 66 ký tự và constructor của
            // Slug ném IllegalArgumentException — lọt khỏi handler thành 500 "Unexpected error".
            String name = "a".repeat(64);
            when(organizations.slugExists(new Slug(name))).thenReturn(true);

            assertThatThrownBy(() -> handler.handle(command(name, null)))
                    .isNotInstanceOf(IllegalArgumentException.class)
                    .is(errorCode(IdentityErrorCode.SLUG_ALREADY_TAKEN));
        }

        @Test
        @DisplayName("slug gõ tay sai định dạng là lỗi 400 của người gọi, không phải 500")
        void slug_go_tay_sai_dinh_dang() {
            // CreateOrganization chỉ kiểm @Size(max = 64); định dạng do constructor của Slug kiểm và
            // ném IllegalArgumentException — GlobalExceptionHandler không map ngoại lệ đó, nên
            // superadmin gõ "Nha Hat" nhận 500 "Unexpected error".
            assertThatThrownBy(() -> handler.handle(command("Nhà hát", "Nha Hat")))
                    .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
            verifyNoInteractions(organizations);
        }
    }

    @Test
    @DisplayName("payload sự kiện mang đủ thông tin để gửi thư mời mà không phải gọi ngược lại identity")
    void payload_su_kien() {
        handler.handle(command("Nhà hát", null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outbox).append(anyString(), anyString(), any(), anyString(), payload.capture());
        assertThat(payload.getValue()).containsKeys("organizationId", "slug", "name", "ownerEmail");
        assertThat(payload.getValue()).doesNotContainKeys("token", "invitationToken", "tokenHash");
    }
}
