// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.domain.model.PurchaseLimits;
import com.nexaticket.catalog.domain.port.PurchaseLimitRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cấu hình trần mua vé (ADR-1014).
 *
 * <p><b>Kiểm trần cứng lúc GHI, không phải lúc giữ chỗ.</b> Tổ chức đặt giá trị vượt trần nền tảng
 * thì bị từ chối ngay tại màn hình cấu hình, nơi họ hiểu chuyện gì đang xảy ra và sửa được. Kiểm ở
 * đường giữ chỗ sẽ bắt 10k request đồng thời phải tính {@code min()}, và người bị báo lỗi lại là
 * khách hàng — người không làm gì sai và không sửa được gì.
 */
@Service
public class ConfigurePurchaseLimitsHandler {

    private final PurchaseLimitRepository limits;

    public ConfigurePurchaseLimitsHandler(PurchaseLimitRepository limits) {
        this.limits = limits;
    }

    /**
     * Trần ở dạng dữ liệu thuần cho tầng interfaces.
     *
     * <p>{@code null} ở một trường nghĩa là kế thừa tầng trên. Cố ý không dùng thẳng value object
     * của domain: controller chỉ được nói chuyện với tầng application (tactical-ddd.md §7).
     */
    public record Limits(
            Integer maxSeatedPerHold,
            Integer maxStandingPerHold,
            Integer maxUnitsPerHold,
            Integer maxTicketsPerCustomer) {

        PurchaseLimits toDomain() {
            return new PurchaseLimits(maxSeatedPerHold, maxStandingPerHold, maxUnitsPerHold, maxTicketsPerCustomer);
        }

        static Limits from(PurchaseLimits limits) {
            return new Limits(
                    limits.maxSeatedPerHold(),
                    limits.maxStandingPerHold(),
                    limits.maxUnitsPerHold(),
                    limits.maxTicketsPerCustomer());
        }
    }

    @Transactional
    public void saveOrganizationDefaults(UUID organizationId, Limits request) {
        PurchaseLimits requested = request.toDomain();
        PurchaseLimits ceiling = limits.platformCeiling();
        if (requested.exceeds(ceiling)) {
            throw new ApiException(
                    CatalogErrorCode.LIMIT_EXCEEDS_PLATFORM_CEILING,
                    "Requested limit exceeds the platform ceiling",
                    Map.of("platformCeiling", Limits.from(ceiling)));
        }
        limits.saveOrganizationDefaults(organizationId, requested);
    }

    /**
     * Superadmin đổi trần cứng.
     *
     * <p>Hạ trần cứng <b>không</b> làm hỏng cấu hình đang lưu của các tổ chức: giá trị vượt sẽ
     * được kẹp lại bằng {@code LEAST()} ở bước materialize. Nếu bắt buộc mọi tổ chức phải sửa
     * trước thì trên thực tế nền tảng sẽ không bao giờ dám hạ trần.
     */
    @Transactional
    public void savePlatformCeiling(Limits ceiling, UUID updatedBy) {
        limits.savePlatformCeiling(ceiling.toDomain(), updatedBy);
    }

    /** Giá trị hiệu lực và nguồn kế thừa — màn hình cấu hình phải hiện rõ cả hai (ADR-1014). */
    @Transactional(readOnly = true)
    public EffectiveLimits effectiveFor(UUID organizationId) {
        PurchaseLimits platform = limits.platformCeiling();
        PurchaseLimits organization = limits.organizationDefaults(organizationId);
        var effective = PurchaseLimits.resolve(PurchaseLimits.INHERIT_ALL, organization, platform);
        return new EffectiveLimits(
                effective.maxSeatedPerHold(),
                effective.maxStandingPerHold(),
                effective.maxUnitsPerHold(),
                effective.maxTicketsPerCustomer(),
                Limits.from(organization),
                Limits.from(platform));
    }

    /**
     * Giá trị hiệu lực kèm nguồn kế thừa.
     *
     * <p>Ba tầng cấu hình là ba chỗ để nhìn khi debug "sao khách này không mua được", nên màn hình
     * phải hiện rõ giá trị nào đang áp dụng và nó đến từ đâu — không chỉ hiện ô nhập (ADR-1014).
     *
     * @param organizationOverride giá trị tổ chức đã đặt; trường null nghĩa là kế thừa
     * @param platformCeiling trần cứng của nền tảng
     */
    public record EffectiveLimits(
            int maxSeatedPerHold,
            int maxStandingPerHold,
            int maxUnitsPerHold,
            int maxTicketsPerCustomer,
            Limits organizationOverride,
            Limits platformCeiling) {}
}
