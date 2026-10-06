// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory;

import com.nexaticket.platform.test.ArchitectureRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.nexaticket.inventory")
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_thuan_nghiep_vu = ArchitectureRules.domainIsFrameworkFree();

    @ArchTest
    static final ArchRule chieu_phu_thuoc_hexagonal = ArchitectureRules.hexagonalLayers("com.nexaticket.inventory");

    @ArchTest
    static final ArchRule khong_import_cheo_context = ArchitectureRules.noCrossContextImports("inventory");
}
