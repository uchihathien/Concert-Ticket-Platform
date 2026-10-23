// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.nexaticket.aichatbox.domain.port.InventoryClientPort;
import com.nexaticket.aichatbox.domain.port.OrderingClientPort;
import com.nexaticket.platform.test.ArchitectureRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.UUID;

@AnalyzeClasses(packages = "com.nexaticket.aichatbox")
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_thuan_nghiep_vu = ArchitectureRules.domainIsFrameworkFree();

    @ArchTest
    static final ArchRule chieu_phu_thuoc_hexagonal = ArchitectureRules.hexagonalLayers("com.nexaticket.aichatbox");

    @ArchTest
    static final ArchRule khong_import_cheo_context = ArchitectureRules.noCrossContextImports("aichatbox");

    /**
     * ADR-2001: tool của mô hình KHÔNG được giữ chỗ hay tạo đơn. Chỉ endpoint xác nhận (ngoài package
     * agent) mới gọi inventory/ordering — mô hình đọc sai ý hay mô tả sự kiện chứa chỉ thị độc hại
     * thì cùng lắm là một đề xuất không ai bấm.
     */
    @ArchTest
    static final ArchRule agent_khong_giu_cho = noClasses()
            .that()
            .resideInAPackage("..application.agent..")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(InventoryClientPort.class);

    @ArchTest
    static final ArchRule agent_khong_tao_don = noClasses()
            .that()
            .resideInAPackage("..application.agent..")
            .should()
            .callMethod(OrderingClientPort.class, "placeOrder", UUID.class, String.class, String.class);
}
