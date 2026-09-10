// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.access;

/**
 * Việc cụ thể một người được phép làm.
 *
 * <p>Đây là bản dịch sang code của bảng trong {@code docs/00-discovery/rbac-permission-matrix.md}.
 * Trước khi có enum này, ma trận đó tồn tại ở ba nơi: tài liệu, ba hàm boolean trên {@link Role},
 * và sáu bản sao của {@code requireOrgAdmin} chép tay trong identity-service. Ba nơi thì có ba
 * phiên bản, và phiên bản sai là phiên bản đang chạy.
 *
 * <h2>Vì sao là enum chứ không phải một bảng trong database</h2>
 *
 * <p>Bảng chỉ đáng khi vai trò do người dùng tự định nghĩa lúc chạy. Ở đây tập vai trò là cố định
 * và do thiết kế hệ thống quy định — một bảng không ai ghi vào là một bảng sẽ lệch dần khỏi đoạn
 * code đọc nó, và không có gì báo. Enum thì trình biên dịch giữ hộ: thêm một quyền mà quên gán cho
 * vai trò nào là một dòng thiếu người ta thấy ngay khi đọc {@link Role#permissions()}.
 *
 * <p>Ma trận vẫn <b>đọc được từ API</b> qua {@code GET /v1/roles}, nên giao diện quản trị hiện được
 * bảng phân quyền mà không phải hard-code tên vai trò.
 *
 * <h2>Phạm vi</h2>
 *
 * <p>Quyền {@code PLATFORM_*} thuộc về nền tảng và không gắn với tổ chức nào; mọi quyền còn lại
 * luôn được hỏi <b>kèm một tổ chức</b> — "được quản lý sự kiện" không có nghĩa gì nếu không nói rõ
 * là sự kiện của tổ chức nào (ADR-0008).
 */
public enum Permission {

    // --- Phạm vi tổ chức ---------------------------------------------------

    /** Dựng và sửa địa điểm, sự kiện, suất diễn, hạng vé. */
    CATALOG_MANAGE,
    /** Đưa sự kiện lên bán và rút xuống — hành động sinh ra tồn kho. */
    EVENT_PUBLISH,
    /** Quét vé vào cửa. */
    CHECKIN_SCAN,
    /** Xem số vé bán và doanh thu của tổ chức. */
    ORG_METRICS_VIEW,
    /** Mời, đổi vai trò, gỡ thành viên. */
    ORG_MEMBERS_MANAGE,
    /** Đổi tên tổ chức và hồ sơ pháp nhân. */
    ORG_PROFILE_MANAGE,
    /** Đặt trần mua vé mặc định của tổ chức (ADR-1014). */
    ORG_LIMITS_SET,
    /** Buộc đăng xuất một thành viên của tổ chức. */
    ORG_SESSION_REVOKE,
    /** Đọc nhật ký kiểm toán của chính tổ chức mình. */
    ORG_AUDIT_READ,

    // --- Phạm vi nền tảng --------------------------------------------------

    /** Tạo, khoá, mở khoá tổ chức (ADR-1010). */
    PLATFORM_ORG_MANAGE,
    /** Khai khung concert chuẩn dùng chung cho các tổ chức. */
    PLATFORM_TEMPLATE_MANAGE,
    /** Vô hiệu hoá tài khoản, thu hồi phiên đăng nhập ở phạm vi toàn hệ thống. */
    PLATFORM_USER_MANAGE,
    /**
     * Sổ cái, số dư, hoa hồng, lịch chi trả.
     *
     * <p>Cố ý KHÔNG có phiên bản phạm vi tổ chức: ADR-1010 quy định tổ chức chỉ thấy số vé bán và
     * tiền đã bán. Một quyền không tồn tại là ranh giới khó đi vòng hơn một quyền tồn tại nhưng
     * chưa gán cho ai.
     */
    PLATFORM_FINANCE_VIEW,
    /** Đọc nhật ký kiểm toán xuyên tổ chức. */
    PLATFORM_AUDIT_READ;

    /** Quyền của nền tảng thì không hỏi kèm tổ chức — xem {@code TenantContext.requirePermission}. */
    public boolean isPlatformScoped() {
        return name().startsWith("PLATFORM_");
    }
}
