// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexaticket.identity.domain.model.UserStatus;
import com.nexaticket.identity.domain.port.SessionRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.domain.port.UserRepository.UserRecord;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Cửa xác thực của identity: token hợp lệ về chữ ký vẫn bị từ chối ở đây khi tài khoản bị khoá,
 * token phát trước mốc thu hồi, hoặc phiên đã bị thu hồi.
 *
 * <p>{@code SessionRevocationIT} kiểm cả chuỗi trên PostgreSQL; ở đây kiểm từng nhánh riêng lẻ và
 * những nhánh IT khó dựng — email trùng {@code sub} khác, token không có {@code sid}.
 */
class LocalMembershipLookupTest {

    private static final Instant ISSUED = Instant.parse("2026-09-08T10:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final SuperAdminBootstrap superAdmins = mock(SuperAdminBootstrap.class);
    private final LocalMembershipLookup lookup = new LocalMembershipLookup(jdbc, users, sessions, superAdmins);

    private final UserId id = UserId.of(UUID.randomUUID());

    private UserRecord record(UserStatus status, Instant validFrom, boolean superAdmin) {
        return new UserRecord(id, "sub-1", "a@example.com", "A", null, superAdmin, status, validFrom);
    }

    private static MembershipLookup.Claims claims(String sid, Instant issuedAt) {
        return new MembershipLookup.Claims("sub-1", "a@example.com", "A", sid, issuedAt);
    }

    @BeforeEach
    void activeUser() {
        when(users.findByIdpSubject("sub-1")).thenReturn(Optional.of(record(UserStatus.ACTIVE, null, false)));
    }

    @Test
    @DisplayName("người dùng hợp lệ: trả principal, KHÔNG ghi gì xuống bảng users trên đường nóng")
    void nguoi_dung_hop_le() {
        MembershipLookup.Principal principal = lookup.resolve(claims("sid-1", ISSUED));

        assertThat(principal).isNotNull();
        assertThat(principal.userId()).isEqualTo(id);
        assertThat(principal.superAdmin()).isFalse();
        verify(users, never()).upsertByIdpSubject(anyString(), anyString(), any());
        verify(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    @DisplayName("tài khoản bị vô hiệu hoá thì không xác thực được")
    void tai_khoan_bi_khoa() {
        when(users.findByIdpSubject("sub-1")).thenReturn(Optional.of(record(UserStatus.DISABLED, null, false)));

        assertThat(lookup.resolve(claims(null, ISSUED))).isNull();
    }

    @Test
    @DisplayName("token phát trước mốc thu hồi toàn bộ phiên bị từ chối")
    void token_truoc_moc_thu_hoi() {
        when(users.findByIdpSubject("sub-1"))
                .thenReturn(Optional.of(record(UserStatus.ACTIVE, ISSUED.plusSeconds(1), false)));

        assertThat(lookup.resolve(claims(null, ISSUED))).isNull();
    }

    @Test
    @DisplayName("phiên đã bị thu hồi bị từ chối; phiên khác của cùng người vẫn vào được")
    void phien_bi_thu_hoi() {
        when(sessions.isRevoked("sid-cu")).thenReturn(true);

        assertThat(lookup.resolve(claims("sid-cu", ISSUED))).isNull();
        assertThat(lookup.resolve(claims("sid-moi", ISSUED))).isNotNull();
    }

    @Test
    @DisplayName("token không mang sid thì không tra bảng phiên thu hồi")
    void khong_co_sid() {
        lookup.resolve(claims(null, ISSUED));

        verify(sessions, never()).isRevoked(any());
    }

    @Test
    @DisplayName("lần đầu đăng nhập không có email: không tạo người dùng không liên lạc được")
    void lan_dau_khong_email() {
        when(users.findByIdpSubject("sub-moi")).thenReturn(Optional.empty());

        assertThat(lookup.resolve(new MembershipLookup.Claims("sub-moi", " ", "A")))
                .isNull();
        verify(users, never()).upsertByIdpSubject(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("email đã thuộc sub khác: KHÔNG chuyển tài khoản sang sub mới — đó là chiếm tài khoản")
    void email_thuoc_sub_khac() {
        when(users.findByIdpSubject("sub-ke-gian")).thenReturn(Optional.empty());
        when(users.upsertByIdpSubject("sub-ke-gian", "a@example.com", "A"))
                .thenThrow(new DuplicateKeyException("uq_users_email"));

        assertThat(lookup.resolve(new MembershipLookup.Claims("sub-ke-gian", "a@example.com", "A")))
                .isNull();
        verify(users, never()).changeIdpSubject(any(), anyString());
    }

    @Test
    @DisplayName("email trong danh sách superadmin được cấp quyền ngay ở lần tra này")
    void cap_superadmin() {
        when(superAdmins.grantIfListed(any())).thenReturn(true);

        assertThat(lookup.resolve(claims(null, ISSUED)).superAdmin()).isTrue();
    }

    @Test
    @DisplayName("claims rỗng hoặc thiếu subject thì null, không chạm database")
    void claims_rong() {
        assertThat(lookup.resolve(null)).isNull();
        assertThat(lookup.resolve(new MembershipLookup.Claims(null, "a@example.com", "A")))
                .isNull();
        verify(users, never()).findByIdpSubject(any());
    }
}
