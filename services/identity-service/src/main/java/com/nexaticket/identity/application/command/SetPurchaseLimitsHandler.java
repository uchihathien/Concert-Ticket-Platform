// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.query.PurchaseLimitsView;
import com.nexaticket.identity.domain.port.PurchaseLimitsRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Khai trần mua vé mặc định của tổ chức (ADR-1014).
 *
 * <p>Đây là mảnh còn thiếu của chuỗi kế thừa trần: bảng đã tồn tại từ migration đầu tiên nhưng chưa
 * có đường nào ghi vào, nên tầng "tổ chức" luôn rỗng và mọi suất diễn rơi thẳng về mặc định nền
 * tảng. Chuỗi đầy đủ là: suất diễn → tổ chức → nền tảng, giải một lần lúc publish.
 *
 * <p>Đọc và ghi để chung một lớp vì hai bên chia đúng một luật: {@code null} nghĩa là "kế thừa",
 * không phải "bỏ giới hạn". Tách ra hai chỗ là hai cơ hội để một bên hiểu null theo nghĩa kia.
 *
 * <p>Không kẹp bằng trần nền tảng ở đây. Việc kẹp nằm ở catalog-service lúc publish
 * ({@code PurchaseLimits.resolve}), và nó phải ở đó: trần nền tảng đổi theo cấu hình, nên kẹp lúc
 * ghi sẽ đóng băng giá trị của ngày hôm ghi vào — hạ trần nền tảng sau đó không có tác dụng gì với
 * các tổ chức đã khai.
 */
@Service
public class SetPurchaseLimitsHandler {

    private final PurchaseLimitsRepository limits;
    private final AuditLogger audit;

    public SetPurchaseLimitsHandler(PurchaseLimitsRepository limits, AuditLogger audit) {
        this.limits = limits;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PurchaseLimitsView get(TenantId organizationId) {
        TenantContext.requirePermission(Permission.ORG_LIMITS_SET, organizationId);
        return PurchaseLimitsView.from(limits.find(organizationId).orElseGet(PurchaseLimitsRepository.Limits::empty));
    }

    @Transactional
    public PurchaseLimitsView set(
            TenantId organizationId, Integer seated, Integer standing, Integer units, Integer perCustomer) {
        TenantContext.requirePermission(Permission.ORG_LIMITS_SET, organizationId);
        PurchaseLimitsRepository.Limits before =
                limits.find(organizationId).orElseGet(PurchaseLimitsRepository.Limits::empty);
        PurchaseLimitsRepository.Limits newLimits =
                new PurchaseLimitsRepository.Limits(seated, standing, units, perCustomer);

        limits.save(organizationId, newLimits);
        audit.record(
                "PURCHASE_LIMITS_CHANGED", "organization", organizationId.value(), asMap(before), asMap(newLimits));
        return PurchaseLimitsView.from(newLimits);
    }

    /** Audit ghi cả giá trị null để đọc lại còn phân biệt được "bỏ trống" với "không gửi". */
    private static Map<String, Object> asMap(PurchaseLimitsRepository.Limits value) {
        Map<String, Object> map = new HashMap<>();
        map.put("maxSeatedPerHold", value.maxSeatedPerHold());
        map.put("maxStandingPerHold", value.maxStandingPerHold());
        map.put("maxUnitsPerHold", value.maxUnitsPerHold());
        map.put("maxTicketsPerCustomer", value.maxTicketsPerCustomer());
        return map;
    }
}
