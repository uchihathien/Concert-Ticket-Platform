// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.inventory.domain.model.LimitViolation;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Mã lỗi là hợp đồng với web-customer — đổi mã trạng thái là làm hỏng nhánh giao diện. */
class InventoryErrorCodeTest {

    @Test
    @DisplayName("Mã trạng thái HTTP của từng mã lỗi đúng như đã phát hành")
    void ma_trang_thai_on_dinh() {
        Map<String, Integer> statuses = Arrays.stream(InventoryErrorCode.values())
                .collect(Collectors.toMap(InventoryErrorCode::code, InventoryErrorCode::httpStatus));

        assertThat(statuses)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "SESSION_NOT_FOUND", 404,
                        "SALES_CLOSED", 409,
                        "SEAT_UNAVAILABLE", 409,
                        "ZONE_SOLD_OUT", 409,
                        "HOLD_LIMIT_EXCEEDED", 400,
                        "CUSTOMER_LIMIT_EXCEEDED", 409,
                        "HOLD_NOT_FOUND", 404,
                        "HOLD_NOT_OWNED", 403,
                        "HOLD_EXPIRED", 409,
                        "INVENTORY_UNAVAILABLE", 503));
    }

    @ParameterizedTest
    @EnumSource(value = LimitViolation.class, names = "CUSTOMER_TOTAL_EXCEEDED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Vi phạm trần mỗi lần giữ chỗ đều thành HOLD_LIMIT_EXCEEDED")
    void tran_moi_lan_giu(LimitViolation violation) {
        assertThat(InventoryErrorCode.of(violation)).isEqualTo(InventoryErrorCode.HOLD_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("Vượt trần cộng dồn thành CUSTOMER_LIMIT_EXCEEDED — giao diện hiện số vé còn mua được")
    void tran_cong_don() {
        assertThat(InventoryErrorCode.of(LimitViolation.CUSTOMER_TOTAL_EXCEEDED))
                .isEqualTo(InventoryErrorCode.CUSTOMER_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("code() trùng tên hằng — frontend so chuỗi theo đúng tên này")
    void code_trung_ten() {
        assertThat(Arrays.stream(InventoryErrorCode.values())
                        .collect(Collectors.toMap(Function.identity(), InventoryErrorCode::code)))
                .allSatisfy((value, code) -> assertThat(code).isEqualTo(value.name()));
    }
}
