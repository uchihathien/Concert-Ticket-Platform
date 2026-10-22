// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.support;

import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.OrganizationStatus;
import com.nexaticket.identity.domain.model.Slug;
import com.nexaticket.identity.domain.model.UserStatus;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Condition;

/** Dữ liệu dựng sẵn cho unit test của tầng application — cố định, không phụ thuộc đồng hồ thật. */
public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    public static final TenantId ORG = TenantId.of(UUID.fromString("11111111-1111-4111-a111-111111111111"));
    public static final TenantId OTHER_ORG = TenantId.of(UUID.fromString("22222222-2222-4222-a222-222222222222"));

    private Fixtures() {}

    public static UserId newUser() {
        return UserId.of(UUID.randomUUID());
    }

    /** Tổ chức đang hoạt động với đúng những thành viên được truyền vào. */
    public static Organization organization(TenantId id, Map<UserId, Role> members) {
        return organization(id, OrganizationStatus.ACTIVE, members);
    }

    public static Organization organization(TenantId id, OrganizationStatus status, Map<UserId, Role> members) {
        List<Membership> list = new ArrayList<>();
        members.forEach(
                (user, role) -> list.add(new Membership(UUID.randomUUID(), user, role, NOW.minusSeconds(86400))));
        return Organization.rehydrate(
                id, new Slug("to-chuc-thu"), "Tổ chức thử", status, list, NOW.minusSeconds(86400));
    }

    public static UserRepository.UserRecord user(UserId id, String email) {
        return new UserRepository.UserRecord(
                id, "sub-" + id.value(), email, "Người Dùng", null, false, UserStatus.ACTIVE, null);
    }

    public static UserRepository.UserRecord disabledUser(UserId id, String email) {
        return new UserRepository.UserRecord(
                id, "sub-" + id.value(), email, "Người Dùng", null, false, UserStatus.DISABLED, null);
    }

    /** Ngoại lệ nghiệp vụ mang đúng mã lỗi — thứ frontend dựa vào để chọn thông báo. */
    public static Condition<Throwable> errorCode(ErrorCode code) {
        return new Condition<>(
                e -> e instanceof ApiException api && api.errorCode() == code, "ApiException với mã %s", code.code());
    }
}
