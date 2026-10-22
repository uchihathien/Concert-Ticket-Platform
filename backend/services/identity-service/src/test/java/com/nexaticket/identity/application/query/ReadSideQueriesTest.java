// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import static com.nexaticket.identity.application.support.Fixtures.CLOCK;
import static com.nexaticket.identity.application.support.Fixtures.NOW;
import static com.nexaticket.identity.application.support.Fixtures.ORG;
import static com.nexaticket.identity.application.support.Fixtures.OTHER_ORG;
import static com.nexaticket.identity.application.support.Fixtures.errorCode;
import static com.nexaticket.identity.application.support.Fixtures.newUser;
import static com.nexaticket.identity.application.support.Fixtures.organization;
import static com.nexaticket.identity.application.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Đường đọc của identity: mỗi truy vấn tự kiểm quyền, không dựa hoàn toàn vào {@code TenantFilter}
 * — bộ lọc ấy đã từng bị đi vòng một lần (xem {@code TenantPath}).
 */
class ReadSideQueriesTest {

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    @Nested
    @DisplayName("tổ chức, thành viên, lời mời")
    class ToChuc {

        private final OrganizationRepository organizations = mock(OrganizationRepository.class);
        private final InvitationRepository invitations = mock(InvitationRepository.class);
        private final UserRepository users = mock(UserRepository.class);
        private final OrganizationQueries queries = new OrganizationQueries(organizations, invitations, users, CLOCK);

        private final UserId owner = newUser();
        private final UserId staff = newUser();

        private Organization stub() {
            Map<UserId, Role> members = new LinkedHashMap<>();
            members.put(owner, Role.ORG_OWNER);
            members.put(staff, Role.CHECKIN_STAFF);
            Organization org = organization(ORG, members);
            when(organizations.findById(ORG)).thenReturn(Optional.of(org));
            return org;
        }

        @Test
        @DisplayName("người ngoài đọc tổ chức nhận 404 như không tồn tại — kể cả khi filter bị đi vòng")
        void nguoi_ngoai_doc_to_chuc() {
            stub();
            TenantContextFixture.member(newUser(), OTHER_ORG, Role.ORG_OWNER);

            assertThatThrownBy(() -> queries.byId(ORG)).is(errorCode(ErrorCode.Common.NOT_FOUND));
            assertThatThrownBy(() -> queries.members(ORG)).is(errorCode(ErrorCode.Common.NOT_FOUND));
            verifyNoInteractions(organizations, users);
        }

        @Test
        @DisplayName("bảng thành viên kèm email và tên, tra hồ sơ một lần cho cả danh sách")
        void bang_thanh_vien() {
            stub();
            TenantContextFixture.member(staff, ORG, Role.CHECKIN_STAFF);
            when(users.findAllByIds(any())).thenReturn(List.of(user(owner, "owner@example.com")));

            List<MemberView> rows = queries.members(ORG);

            assertThat(rows).hasSize(2);
            assertThat(rows)
                    .filteredOn(row -> row.userId().equals(owner.toString()))
                    .singleElement()
                    .satisfies(row -> assertThat(row.email()).isEqualTo("owner@example.com"));
            // Hồ sơ thiếu (người dùng bị xoá) vẫn hiện dòng, với email null — không làm hỏng cả bảng.
            assertThat(rows)
                    .filteredOn(row -> row.userId().equals(staff.toString()))
                    .singleElement()
                    .satisfies(row -> assertThat(row.email()).isNull());
            verify(users).findAllByIds(any());
        }

        @Test
        @DisplayName("superadmin đọc được tổ chức dù không là thành viên")
        void superadmin_doc_duoc() {
            stub();
            TenantContextFixture.superAdmin(newUser());

            assertThat(queries.byId(ORG).id()).isEqualTo(ORG.toString());
        }

        @Test
        @DisplayName("lời mời đang chờ: chỉ ORG_ADMIN+, không có token hay hash, đánh dấu cái đã hết hạn")
        void loi_moi_dang_cho() {
            TenantContextFixture.member(owner, ORG, Role.ORG_ADMIN);
            Invitation live = Invitation.rehydrate(
                    UUID.randomUUID(), ORG, "a@example.com", Role.ORG_ADMIN, "hash-a", NOW.plusSeconds(60), null);
            Invitation stale = Invitation.rehydrate(
                    UUID.randomUUID(), ORG, "b@example.com", Role.ORG_ADMIN, "hash-b", NOW.minusSeconds(60), null);
            when(invitations.findPending(ORG)).thenReturn(List.of(live, stale));

            List<InvitationView> views = queries.pendingInvitations(ORG);

            assertThat(views).extracting(InvitationView::expired).containsExactly(false, true);
            assertThat(views.toString()).doesNotContain("hash-a").doesNotContain("hash-b");
        }

        @Test
        @DisplayName("nhân viên soát vé không xem được danh sách lời mời")
        void staff_khong_xem_loi_moi() {
            TenantContextFixture.member(staff, ORG, Role.CHECKIN_STAFF);

            assertThatThrownBy(() -> queries.pendingInvitations(ORG)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(invitations);
        }

        @Test
        @DisplayName("hộp thư lời mời tra theo email của chính người đăng nhập, kèm tên tổ chức")
        void hop_thu_loi_moi() {
            stub();
            UserId me = newUser();
            TenantContextFixture.customer(me);
            when(users.findById(me)).thenReturn(Optional.of(user(me, "me@example.com")));
            Invitation mine = Invitation.rehydrate(
                    UUID.randomUUID(), ORG, "me@example.com", Role.EVENT_MANAGER, "h", NOW.plusSeconds(60), null);
            when(invitations.findPendingForEmail("me@example.com")).thenReturn(List.of(mine));

            List<MyInvitationView> inbox = queries.myPendingInvitations();

            assertThat(inbox).singleElement().satisfies(view -> {
                assertThat(view.organizationName()).isEqualTo("Tổ chức thử");
                assertThat(view.role()).isEqualTo("EVENT_MANAGER");
            });
            verify(invitations).findPendingForEmail("me@example.com");
        }

        @Test
        @DisplayName("danh sách toàn hệ thống chỉ superadmin; trang bị kẹp về 200 và offset âm về 0")
        void danh_sach_toan_he_thong() {
            TenantContextFixture.member(owner, ORG, Role.ORG_OWNER);
            assertThatThrownBy(() -> queries.all(50, 0)).is(errorCode(ErrorCode.Common.FORBIDDEN));

            TenantContextFixture.superAdmin(newUser());
            queries.all(10_000, -5);
            verify(organizations).findAll(200, 0);
        }

        @Test
        @DisplayName("hồ sơ của tôi kèm vai trò trong từng tổ chức")
        void ho_so() {
            Organization org = stub();
            TenantContextFixture.member(staff, ORG, Role.CHECKIN_STAFF);
            when(users.findById(staff)).thenReturn(Optional.of(user(staff, "nv@example.com")));
            when(organizations.findAllByMember(staff)).thenReturn(List.of(org));

            ProfileView profile = queries.currentProfile();

            assertThat(profile.email()).isEqualTo("nv@example.com");
            assertThat(profile.superAdmin()).isFalse();
            assertThat(profile.organizations()).singleElement().satisfies(m -> assertThat(m.role())
                    .isEqualTo("CHECKIN_STAFF"));
        }

        @Test
        @DisplayName("địa chỉ liên hệ cho service nội bộ: 404 khi không có, không trả idpSubject hay cờ superadmin")
        void lien_he() {
            UserId id = newUser();
            when(users.findById(id)).thenReturn(Optional.of(user(id, "a@example.com")));

            UserContactView contact = queries.contactOf(id);
            assertThat(contact).isEqualTo(new UserContactView(id.value().toString(), "a@example.com", "Người Dùng"));

            assertThatThrownBy(() -> queries.contactOf(newUser())).is(errorCode(IdentityErrorCode.USER_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("ma trận phân quyền")
    class MaTran {

        private final AccessQueries queries = new AccessQueries();

        @Test
        @DisplayName("quyền của tôi: theo từng tổ chức, không bịa tổ chức cho superadmin")
        void quyen_cua_toi() {
            UserId me = newUser();
            TenantContextFixture.use(new TenantScope(me, Map.of(ORG, Role.EVENT_MANAGER), ORG, false));

            AccessQueries.MyPermissions mine = queries.myPermissions();

            assertThat(mine.platformPermissions()).isEmpty();
            assertThat(mine.organizations()).containsOnlyKeys(ORG.toString());
            assertThat(mine.organizations().get(ORG.toString()))
                    .contains(Permission.CATALOG_MANAGE.name())
                    .doesNotContain(Permission.ORG_MEMBERS_MANAGE.name())
                    .isSorted();

            TenantContextFixture.superAdmin(me);
            AccessQueries.MyPermissions admin = queries.myPermissions();
            assertThat(admin.organizations()).isEmpty();
            assertThat(admin.platformPermissions()).contains(Permission.PLATFORM_ORG_MANAGE.name());
        }

        @Test
        @DisplayName("chưa đăng nhập thì 401")
        void chua_dang_nhap() {
            assertThatThrownBy(queries::myPermissions).is(errorCode(ErrorCode.Common.UNAUTHENTICATED));
        }

        @Test
        @DisplayName("toàn bộ vai trò kèm phạm vi, đủ mọi vai trò của enum")
        void toan_bo_vai_tro() {
            assertThat(queries.allRoles()).hasSize(Role.values().length);
            assertThat(queries.allRoles())
                    .filteredOn(view -> view.role().equals("SUPER_ADMIN"))
                    .singleElement()
                    .satisfies(view -> assertThat(view.scope()).isEqualTo("GLOBAL"));
        }
    }

    @Nested
    @DisplayName("nhật ký kiểm toán")
    class NhatKy {

        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final AuditQueries queries = new AuditQueries(jdbc, new ObjectMapper());

        @SuppressWarnings("unchecked")
        private Object[] paramsOfQuery() {
            ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).query(anyString(), any(RowMapper.class), params.capture());
            return params.getValue();
        }

        @Test
        @DisplayName("nhật ký của tổ chức lọc organization_id ngay trong SQL, tham số đầu là tổ chức đang thao tác")
        void loc_theo_to_chuc() {
            TenantContextFixture.member(newUser(), ORG, Role.ORG_ADMIN);

            queries.forOrganization(ORG, null, null, null, 50, 0);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
            assertThat(sql.getValue()).contains("WHERE organization_id = ?");
        }

        @Test
        @DisplayName("tham số: tổ chức trước, rồi bộ lọc theo cặp, trang kẹp 1..200, offset âm thành 0")
        void tham_so() {
            TenantContextFixture.member(newUser(), ORG, Role.ORG_OWNER);

            queries.forOrganization(ORG, "MEMBER_REMOVED", NOW, NOW.plusSeconds(60), 5_000, -3);

            Object[] params = paramsOfQuery();
            assertThat(params).hasSize(9);
            assertThat(params[0]).isEqualTo(ORG.value());
            assertThat(params[1]).isEqualTo("MEMBER_REMOVED");
            assertThat(params[2]).isEqualTo("MEMBER_REMOVED");
            assertThat(params[7]).isEqualTo(200);
            assertThat(params[8]).isEqualTo(0);
        }

        @Test
        @DisplayName("trang 0 hay âm rơi về mặc định 50, không phải 'không giới hạn'")
        void trang_mac_dinh() {
            TenantContextFixture.superAdmin(newUser());

            queries.forPlatform(null, null, null, 0, 0);

            assertThat(paramsOfQuery()[6]).isEqualTo(50);
        }

        @Test
        @DisplayName("EVENT_MANAGER không đọc nhật ký; quản trị tổ chức không đọc nhật ký nền tảng")
        void quyen_doc() {
            TenantContextFixture.member(newUser(), ORG, Role.EVENT_MANAGER);
            assertThatThrownBy(() -> queries.forOrganization(ORG, null, null, null, 50, 0))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));

            TenantContextFixture.member(newUser(), ORG, Role.ORG_OWNER);
            assertThatThrownBy(() -> queries.forPlatform(null, null, null, 50, 0))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));
            assertThatThrownBy(() -> queries.forOrganization(OTHER_ORG, null, null, null, 50, 0))
                    .is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("hành động lọc đi vào tham số, không bao giờ nối vào chuỗi SQL")
        void khong_noi_chuoi_sql() {
            TenantContextFixture.superAdmin(newUser());
            String injection = "X' OR '1'='1";

            queries.forPlatform(injection, null, null, 10, 0);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).query(sql.capture(), any(RowMapper.class), params.capture());
            assertThat(params.getValue()[0]).isEqualTo(injection);
            assertThat(sql.getValue()).doesNotContain(injection);
        }
    }
}
