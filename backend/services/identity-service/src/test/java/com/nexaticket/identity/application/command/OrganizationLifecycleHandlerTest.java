// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import static com.nexaticket.identity.application.support.Fixtures.ORG;
import static com.nexaticket.identity.application.support.Fixtures.errorCode;
import static com.nexaticket.identity.application.support.Fixtures.newUser;
import static com.nexaticket.identity.application.support.Fixtures.organization;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.OrganizationStatus;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

/**
 * Đổi tên (việc của tổ chức) và khoá / mở khoá (việc của nền tảng).
 *
 * <p>Ranh giới đáng kiểm nhất: chủ sở hữu tổ chức — người có mọi quyền ở nhà mình — vẫn không tự
 * mở khoá được. Nếu được thì việc khoá chẳng có nghĩa gì.
 */
class OrganizationLifecycleHandlerTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final OrganizationLifecycleHandler handler = new OrganizationLifecycleHandler(organizations, audit);

    private final UserId owner = newUser();

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    private Organization stub(OrganizationStatus status) {
        Organization org = organization(ORG, status, Map.of(owner, Role.ORG_OWNER));
        when(organizations.findById(ORG)).thenReturn(Optional.of(org));
        return org;
    }

    @Nested
    @DisplayName("khoá tổ chức")
    class Khoa {

        @Test
        @DisplayName("superadmin khoá được tổ chức đang hoạt động, có ghi nhật ký ACTIVE → SUSPENDED")
        void superadmin_khoa() {
            Organization org = stub(OrganizationStatus.ACTIVE);
            TenantContextFixture.superAdmin(newUser());

            handler.suspend(ORG);

            assertThat(org.status()).isEqualTo(OrganizationStatus.SUSPENDED);
            verify(organizations).save(org);
            verify(audit)
                    .record(
                            "ORGANIZATION_SUSPENDED",
                            "organization",
                            ORG.value(),
                            Map.of("status", "ACTIVE"),
                            Map.of("status", "SUSPENDED"));
        }

        @ParameterizedTest
        @EnumSource(
                value = Role.class,
                names = {"ORG_OWNER", "ORG_ADMIN"})
        @DisplayName("chủ sở hữu và quản trị viên tổ chức không tự khoá được tổ chức mình")
        void to_chuc_khong_tu_khoa(Role role) {
            TenantContextFixture.member(owner, ORG, role);

            assertThatThrownBy(() -> handler.suspend(ORG)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(organizations, audit);
        }

        @Test
        @DisplayName("khoá tổ chức đã bị khoá thì báo ORGANIZATION_SUSPENDED, không ghi nhật ký lần hai")
        void khoa_hai_lan() {
            stub(OrganizationStatus.SUSPENDED);
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.suspend(ORG)).is(errorCode(IdentityErrorCode.ORGANIZATION_SUSPENDED));
            verify(organizations, never()).save(any());
            verifyNoInteractions(audit);
        }

        @Test
        @DisplayName("tổ chức không tồn tại thì ORGANIZATION_NOT_FOUND")
        void khong_ton_tai() {
            when(organizations.findById(ORG)).thenReturn(Optional.empty());
            TenantContextFixture.superAdmin(newUser());

            assertThatThrownBy(() -> handler.suspend(ORG)).is(errorCode(IdentityErrorCode.ORGANIZATION_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("mở khoá tổ chức")
    class MoKhoa {

        @Test
        @DisplayName("superadmin mở khoá được, nhật ký ghi SUSPENDED → ACTIVE")
        void superadmin_mo_khoa() {
            Organization org = stub(OrganizationStatus.SUSPENDED);
            TenantContextFixture.superAdmin(newUser());

            handler.activate(ORG);

            assertThat(org.isActive()).isTrue();
            verify(organizations).save(org);
            verify(audit)
                    .record(
                            "ORGANIZATION_ACTIVATED",
                            "organization",
                            ORG.value(),
                            Map.of("status", "SUSPENDED"),
                            Map.of("status", "ACTIVE"));
        }

        @Test
        @DisplayName("chủ sở hữu tổ chức bị khoá không tự mở khoá được")
        void chu_so_huu_khong_tu_mo() {
            stub(OrganizationStatus.SUSPENDED);
            TenantContextFixture.member(owner, ORG, Role.ORG_OWNER);

            assertThatThrownBy(() -> handler.activate(ORG)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verify(organizations, never()).save(any());
        }

        @Test
        @DisplayName("mở khoá một tổ chức đang hoạt động không được để lại dòng nhật ký nói sai trạng thái trước đó")
        void mo_khoa_to_chuc_dang_hoat_dong() {
            // `suspend` từ chối khi đã khoá, nhưng `activate` không kiểm gì: nó vẫn lưu và ghi một
            // dòng nhật ký "before: SUSPENDED" cho một tổ chức CHƯA TỪNG bị khoá. Nhật ký kiểm toán
            // là thứ duy nhất tổ chức dựa vào để đối chiếu thao tác của nền tảng (ADR-1010 hệ quả #3)
            // — một dòng sai sự thật ở đó tệ hơn không có dòng nào.
            stub(OrganizationStatus.ACTIVE);
            TenantContextFixture.superAdmin(newUser());

            try {
                handler.activate(ORG);
            } catch (ApiException rejected) {
                // Từ chối cũng là một cách đúng — giống như `suspend` làm.
            }

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
            verify(audit, atMost(1)).record(anyString(), anyString(), any(), before.capture(), any());
            assertThat(before.getAllValues())
                    .as("nhật ký không được khẳng định tổ chức đang bị khoá trước lệnh này")
                    .noneMatch(map -> map != null && "SUSPENDED".equals(map.get("status")));
        }
    }

    @Nested
    @DisplayName("đổi tên")
    class DoiTen {

        @Test
        @DisplayName("ORG_ADMIN đổi được tên, nhật ký ghi tên cũ và tên mới đã cắt khoảng trắng")
        void admin_doi_ten() {
            Organization org = stub(OrganizationStatus.ACTIVE);
            TenantContextFixture.member(newUser(), ORG, Role.ORG_ADMIN);

            handler.rename(ORG, "  Tên mới  ");

            assertThat(org.name()).isEqualTo("Tên mới");
            verify(audit)
                    .record(
                            "ORGANIZATION_RENAMED",
                            "organization",
                            ORG.value(),
                            Map.of("name", "Tổ chức thử"),
                            Map.of("name", "Tên mới"));
        }

        @Test
        @DisplayName("EVENT_MANAGER không đổi được tên tổ chức")
        void quan_ly_su_kien_khong_doi_duoc() {
            TenantContextFixture.member(newUser(), ORG, Role.EVENT_MANAGER);

            assertThatThrownBy(() -> handler.rename(ORG, "X")).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(organizations);
        }

        @Test
        @DisplayName("tên rỗng không được lưu")
        void ten_rong() {
            stub(OrganizationStatus.ACTIVE);
            TenantContextFixture.member(newUser(), ORG, Role.ORG_OWNER);

            assertThatThrownBy(() -> handler.rename(ORG, "  ")).isInstanceOf(IllegalArgumentException.class);
            verify(organizations, never()).save(any());
            verify(audit, never()).record(eq("ORGANIZATION_RENAMED"), anyString(), any(), any(), any());
        }
    }
}
