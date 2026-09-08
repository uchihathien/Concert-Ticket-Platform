// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.port.UserRepository;

/**
 * Một dòng trong bảng thành viên.
 *
 * <p>{@code email} và {@code fullName} có thể null khi dựng từ mỗi {@link Membership}: aggregate
 * {@code Organization} chỉ giữ id người dùng, không giữ hồ sơ của họ — người dùng là bảng riêng và
 * cố ý nằm ngoài aggregate.
 *
 * <p>Màn hình quản lý thành viên thì bắt buộc phải có email: một bảng chỉ toàn UUID thì không ai
 * biết đang gỡ nhầm ai. Nên {@code OrganizationQueries.members} tra thêm hồ sơ và dùng
 * {@link #withUser} để bổ sung — một truy vấn cho cả danh sách, không phải mỗi dòng một truy vấn. *
 * <p>{@code @JsonInclude(ALWAYS)}: identity-service khai {@code default-property-inclusion: non_null}
 * cho toàn service, nên trường null bị bỏ khỏi JSON. Với DTO mà frontend bind thẳng vào form hay
 * bảng thì đó là cái bẫy: kiểu khai là {@code string | null} nhưng thứ nhận được là
 * {@code undefined}, và mọi phép so sánh với null đều trượt. Ở đây trả null tường minh.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MemberView(String userId, String email, String fullName, String role, String joinedAt) {

    public static MemberView from(Membership membership) {
        return new MemberView(
                membership.userId().toString(),
                null,
                null,
                membership.role().name(),
                membership.joinedAt().toString());
    }

    public MemberView withUser(UserRepository.UserRecord user) {
        return user == null ? this : new MemberView(userId, user.email(), user.fullName(), role, joinedAt);
    }
}
