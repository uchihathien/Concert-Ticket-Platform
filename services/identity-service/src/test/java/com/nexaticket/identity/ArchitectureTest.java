// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity;

import com.nexaticket.platform.test.ArchitectureRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Luật kiến trúc chạy như test thường.
 *
 * <p>Viết ngay ở service đầu tiên, không để sau: khi đã có 11 service thì không ai dọn được nữa
 * (plan/backend.md §2).
 */
@AnalyzeClasses(packages = "com.nexaticket.identity")
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_thuan_nghiep_vu = ArchitectureRules.domainIsFrameworkFree();

    @ArchTest
    static final ArchRule chieu_phu_thuoc_hexagonal = ArchitectureRules.hexagonalLayers("com.nexaticket.identity");

    @ArchTest
    static final ArchRule khong_import_cheo_context = ArchitectureRules.noCrossContextImports("identity");
}
