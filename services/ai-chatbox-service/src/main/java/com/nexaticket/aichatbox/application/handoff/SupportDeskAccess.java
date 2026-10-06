// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.handoff;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.platform.security.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cửa quyền của bàn hỗ trợ.
 *
 * <p>Quyền phạm vi nền tảng, không phạm vi tổ chức: khách chat với nền tảng về đơn hàng của chính
 * họ, và một cuộc chat có thể nhắc tới sự kiện của nhiều tổ chức. Mở đường này cho ban tổ chức
 * nghĩa là họ đọc được hội thoại của khách với tổ chức khác.
 */
@Component
public class SupportDeskAccess {

    /** @return id của người trực, lấy từ JWT — không bao giờ từ body request */
    public UUID requireSupportAgent() {
        TenantContext.requirePlatformPermission(Permission.PLATFORM_SUPPORT_HANDLE);
        return TenantContext.requireAuthenticated().userId().value();
    }
}
