// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import static com.nexaticket.identity.application.support.Fixtures.ORG;
import static com.nexaticket.identity.application.support.Fixtures.OTHER_ORG;
import static com.nexaticket.identity.application.support.Fixtures.errorCode;
import static com.nexaticket.identity.application.support.Fixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.identity.application.query.PurchaseLimitsView;
import com.nexaticket.identity.domain.port.PurchaseLimitsRepository;
import com.nexaticket.identity.domain.port.PurchaseLimitsRepository.Limits;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
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

/** Hồ sơ cá nhân và trần mua vé của tổ chức — hai lệnh ghi nhỏ, mỗi cái một luật "null nghĩa là gì". */
class ProfileAndLimitsHandlersTest {

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    @Nested
    @DisplayName("sửa hồ sơ của chính mình")
    class HoSo {

        private final UserRepository users = mock(UserRepository.class);
        private final UpdateProfileHandler handler = new UpdateProfileHandler(users);

        @Test
        @DisplayName("id lấy từ phiên đăng nhập; chuỗi được cắt khoảng trắng")
        void sua_ho_so() {
            UserId me = newUser();
            TenantContextFixture.customer(me);

            handler.handle("  Nguyễn Văn A ", " 0912 345 678 ");

            verify(users).updateProfile(me, "Nguyễn Văn A", "0912 345 678");
        }

        @Test
        @DisplayName("ô bỏ trống thành null — nghĩa là giữ nguyên, không xoá tên người dùng")
        void bo_trong_la_giu_nguyen() {
            UserId me = newUser();
            TenantContextFixture.customer(me);

            handler.handle("", "   ");

            verify(users).updateProfile(me, null, null);
        }

        @Test
        @DisplayName("chưa đăng nhập thì 401, không ghi gì")
        void chua_dang_nhap() {
            assertThatThrownBy(() -> handler.handle("A", null)).is(errorCode(ErrorCode.Common.UNAUTHENTICATED));
            verifyNoInteractions(users);
        }
    }

    @Nested
    @DisplayName("trần mua vé")
    class TranMuaVe {

        private final PurchaseLimitsRepository limits = mock(PurchaseLimitsRepository.class);
        private final AuditLogger audit = mock(AuditLogger.class);
        private final SetPurchaseLimitsHandler handler = new SetPurchaseLimitsHandler(limits, audit);

        @Test
        @DisplayName("chưa khai gì thì đọc ra toàn null — kế thừa trần nền tảng, không phải 'không giới hạn'")
        void chua_khai() {
            TenantContextFixture.member(newUser(), ORG, Role.ORG_ADMIN);
            when(limits.find(ORG)).thenReturn(Optional.empty());

            assertThat(handler.get(ORG)).isEqualTo(new PurchaseLimitsView(null, null, null, null));
        }

        @Test
        @DisplayName("ghi cả bộ trần, nhật ký giữ cả giá trị null để phân biệt 'bỏ trống' với 'không gửi'")
        void ghi_ca_bo() {
            TenantContextFixture.member(newUser(), ORG, Role.ORG_OWNER);
            when(limits.find(ORG)).thenReturn(Optional.of(new Limits(4, null, 10, 20)));

            PurchaseLimitsView view = handler.set(ORG, 6, null, null, 8);

            verify(limits).save(ORG, new Limits(6, null, null, 8));
            assertThat(view).isEqualTo(new PurchaseLimitsView(6, null, null, 8));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
            verify(audit)
                    .record(
                            eq("PURCHASE_LIMITS_CHANGED"),
                            eq("organization"),
                            eq(ORG.value()),
                            before.capture(),
                            after.capture());
            assertThat(before.getValue()).containsEntry("maxUnitsPerHold", 10);
            assertThat(after.getValue())
                    .containsKey("maxUnitsPerHold")
                    .containsEntry("maxUnitsPerHold", null)
                    .containsEntry("maxTicketsPerCustomer", 8);
        }

        @ParameterizedTest
        @EnumSource(
                value = Role.class,
                names = {"EVENT_MANAGER", "CHECKIN_STAFF"})
        @DisplayName("vai trò dưới ORG_ADMIN không đọc cũng không ghi được trần")
        void vai_tro_thap(Role role) {
            TenantContextFixture.member(newUser(), ORG, role);

            assertThatThrownBy(() -> handler.get(ORG)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            assertThatThrownBy(() -> handler.set(ORG, 1, 1, 1, 1)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verifyNoInteractions(limits, audit);
        }

        @Test
        @DisplayName("ORG_ADMIN tổ chức khác không đụng được trần của tổ chức này")
        void to_chuc_khac() {
            TenantContextFixture.member(newUser(), OTHER_ORG, Role.ORG_ADMIN);

            assertThatThrownBy(() -> handler.set(ORG, 1, 1, 1, 1)).is(errorCode(ErrorCode.Common.FORBIDDEN));
            verify(audit, never()).record(anyString(), anyString(), any(), any(), any());
        }
    }
}
