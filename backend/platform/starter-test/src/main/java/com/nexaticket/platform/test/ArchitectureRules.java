// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;

/**
 * Luật kiến trúc dùng chung cho mọi service.
 *
 * <p>Mỗi service tạo một lớp test khai package gốc của mình:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.nexaticket.identity")
 * class ArchitectureTest {
 *     @ArchTest static final ArchRule layers = ArchitectureRules.hexagonalLayers("com.nexaticket.identity");
 *     @ArchTest static final ArchRule domainPure = ArchitectureRules.domainIsFrameworkFree();
 *     @ArchTest static final ArchRule noCross = ArchitectureRules.noCrossContextImports("identity");
 * }
 * }</pre>
 */
public final class ArchitectureRules {

    /** Mọi bounded context của hệ thống. Thêm context mới thì thêm vào đây. */
    private static final List<String> ALL_CONTEXTS =
            List.of("identity", "catalog", "inventory", "ordering", "payment", "ledger", "payout", "ticketing");

    private ArchitectureRules() {}

    /** domain không được biết đến framework — nếu không, mô hình dữ liệu sẽ lấn mô hình nghiệp vụ. */
    public static ArchRule domainIsFrameworkFree() {
        return noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.servlet..",
                        "com.fasterxml.jackson..")
                .because("domain phải thuần nghiệp vụ; persistence và web là adapter (tactical-ddd.md §1)");
    }

    /**
     * Chiều phụ thuộc hexagonal.
     *
     * <p>{@code interfaces} <b>không</b> được chạm thẳng vào {@code domain}: controller đi qua
     * {@code application}, kể cả đường đọc — query service trả DTO của tầng application, không trả
     * aggregate ra ngoài (tactical-ddd.md §7).
     */
    public static ArchRule hexagonalLayers(String basePackage) {
        return layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                // Luật này ép CHIỀU phụ thuộc, không bắt mọi service phải có đủ bốn tầng.
                // Service mới sinh ra chưa có controller, service thuần consumer không có
                // tầng interfaces — cả hai đều hợp lệ.
                .withOptionalLayers(true)
                .layer("domain")
                .definedBy(basePackage + ".domain..")
                .layer("application")
                .definedBy(basePackage + ".application..")
                .layer("infrastructure")
                .definedBy(basePackage + ".infrastructure..")
                .layer("interfaces")
                .definedBy(basePackage + ".interfaces..")
                .whereLayer("interfaces")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("application")
                .mayOnlyBeAccessedByLayers("interfaces", "infrastructure")
                .whereLayer("domain")
                .mayOnlyBeAccessedByLayers("application", "infrastructure");
    }

    /**
     * Chốt giữ cho monorepo không thoái hoá thành monolith: không service nào import class của
     * bounded context <b>khác</b>. Muốn gọi service khác thì sinh client từ OpenAPI của nó.
     *
     * @param ownContext context của chính service đang kiểm — được loại khỏi danh sách cấm
     */
    public static ArchRule noCrossContextImports(String ownContext) {
        List<String> forbidden = new ArrayList<>();
        for (String context : ALL_CONTEXTS) {
            if (!context.equals(ownContext)) {
                forbidden.add("com.nexaticket." + context + "..");
            }
        }
        return noClasses()
                .that()
                .resideInAPackage("com.nexaticket." + ownContext + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(forbidden.toArray(new String[0]))
                .because("cùng repo không có nghĩa là được import chéo context (plan/README.md §2)")
                .allowEmptyShould(true);
    }
}
