// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import com.nexaticket.kernel.id.UserId;
import java.util.Map;
import java.util.UUID;

/**
 * Đặt danh tính khách cho test gọi controller trực tiếp.
 *
 * <p>Nằm cùng package với {@code TenantContext} vì {@code set}/{@code clear} cố ý package-private —
 * mã sản phẩm không có đường nào tự gán danh tính, chỉ filter bảo mật làm việc đó. Cùng cách
 * identity-service dùng {@code TenantContextFixture}.
 */
public final class ChatTenantFixture {

    private ChatTenantFixture() {}

    public static void customer(UUID userId) {
        TenantContext.set(new TenantScope(new UserId(userId), Map.of(), null, false));
    }

    public static void clear() {
        TenantContext.clear();
    }
}
