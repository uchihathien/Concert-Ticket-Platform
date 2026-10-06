// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Hồ sơ của người đang đăng nhập, kèm các tổ chức họ thuộc về.
 *
 * <p>Gộp hai thứ vào một phản hồi vì chúng luôn được dùng cùng nhau: mọi app đều cần biết "tôi là
 * ai" và "tôi thao tác được cho tổ chức nào" ngay ở lần dựng khung màn hình đầu tiên. Tách thành
 * hai endpoint sẽ thành hai vòng khứ hồi nối tiếp trước khi vẽ được gì.
 *
 * @param superAdmin frontend dùng để quyết định có hiện app nền tảng hay không. Đây là gợi ý hiển
 *     thị, KHÔNG phải cửa quyền — cửa quyền thật nằm ở backend, trên từng endpoint. *
 * <p>{@code @JsonInclude(ALWAYS)}: identity-service khai {@code default-property-inclusion: non_null}
 * cho toàn service, nên trường null bị bỏ khỏi JSON. Với DTO mà frontend bind thẳng vào form hay
 * bảng thì đó là cái bẫy: kiểu khai là {@code string | null} nhưng thứ nhận được là
 * {@code undefined}, và mọi phép so sánh với null đều trượt. Ở đây trả null tường minh.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProfileView(
        String id, String email, String fullName, String phone, boolean superAdmin, List<Membership> organizations) {

    /** Một tổ chức kèm vai trò của chính người này trong đó. */
    public record Membership(String organizationId, String slug, String name, String status, String role) {}
}
