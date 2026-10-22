// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.kernel.id.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link TenantPath}: đọc tổ chức từ đường dẫn thô — chung cho {@code TenantFilter} và
 * {@code PermissionGuard}.
 *
 * <p>Điều quan trọng nhất không phải là đọc đúng UUID chuẩn, mà là <b>mọi biểu diễn khác</b> của
 * đoạn tổ chức đều thành {@code malformed} chứ không thành "không có tổ chức" — vì nhánh "không có
 * tổ chức" là nhánh dự phòng đã từng biến một ký tự mã hoá phần trăm thành một lần vượt rào.
 */
class TenantPathTest {

    private static final String ORG = "11111111-1111-4111-a111-111111111111";

    @Test
    @DisplayName("UUID chuẩn trên đường dẫn: present và đọc ra đúng tổ chức")
    void uuid_chuan_doc_dung_to_chuc() {
        TenantPath.Match match = TenantPath.organizationOf("/v1/organizations/" + ORG + "/members");

        assertThat(match.present()).isTrue();
        assertThat(match.malformed()).isFalse();
        assertThat(match.tenant()).isEqualTo(TenantId.parse(ORG));
    }

    @Test
    @DisplayName("UUID viết hoa vẫn là cùng tổ chức")
    void uuid_viet_hoa_cung_to_chuc() {
        TenantPath.Match match = TenantPath.organizationOf("/v1/organizations/" + ORG.toUpperCase() + "/events");

        assertThat(match.tenant()).isEqualTo(TenantId.parse(ORG));
    }

    @Test
    @DisplayName("đoạn tổ chức ở cuối đường dẫn, không có gì phía sau")
    void doan_to_chuc_o_cuoi() {
        assertThat(TenantPath.organizationOf("/v1/organizations/" + ORG).tenant())
                .isEqualTo(TenantId.parse(ORG));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"/v1/events", "/v1/me/orders", "/internal/memberships", "/v1/organizations", ""})
    @DisplayName("không có đoạn /organizations/{x}: absent, không malformed")
    void khong_co_doan_to_chuc(String uri) {
        TenantPath.Match match = TenantPath.organizationOf(uri);

        assertThat(match.present()).isFalse();
        assertThat(match.malformed()).isFalse();
        assertThat(match.tenant()).isNull();
    }

    @Test
    @DisplayName("uri null: absent, không ném NPE từ trong filter")
    void uri_null_la_absent() {
        assertThat(TenantPath.organizationOf(null).present()).isFalse();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
            strings = {
                // Mã hoá phần trăm một ký tự: chính lỗ hổng đã xảy ra.
                "/v1/organizations/%3111111111-1111-4111-a111-111111111111/members",
                // Mã hoá hai lần.
                "/v1/organizations/%253111111111-1111-4111-a111-111111111111/members",
                // 36 gạch nối: khớp mẫu lỏng nhưng UUID.fromString từ chối.
                "/v1/organizations/------------------------------------/members",
                // Thiếu nhóm, thừa ký tự, ký tự không phải hex.
                "/v1/organizations/11111111-1111-4111-a111/members",
                "/v1/organizations/11111111-1111-4111-a111-1111111111112/members",
                "/v1/organizations/g1111111-1111-4111-a111-111111111111/members",
                // Tham số ma trận dính vào đoạn.
                "/v1/organizations/11111111-1111-4111-a111-111111111111;x=1/members",
                // Không phải UUID.
                "/v1/organizations/acme/members",
                // Chữ số Ả Rập-Ấn (unicode tương đương '1').
                "/v1/organizations/١١111111-1111-4111-a111-111111111111/members"
            })
    @DisplayName("đoạn tổ chức có mặt nhưng không phải UUID chuẩn: malformed để người gọi từ chối")
    void bien_the_khong_chuan_la_malformed(String uri) {
        TenantPath.Match match = TenantPath.organizationOf(uri);

        assertThat(match.present()).isTrue();
        assertThat(match.malformed()).isTrue();
        assertThat(match.tenant()).isNull();
    }

    @Test
    @DisplayName("route nền tảng /v1/platform/organizations/{id} vẫn được đọc — miễn trừ là việc của filter")
    void route_nen_tang_van_doc_duoc() {
        TenantPath.Match match = TenantPath.organizationOf("/v1/platform/organizations/" + ORG + "/suspend");

        assertThat(match.tenant()).isEqualTo(TenantId.parse(ORG));
    }

    @Test
    @DisplayName("hai đoạn tổ chức: lấy đoạn ĐẦU — bộ lọc và bộ chặn quyền luôn đọc cùng một đoạn")
    void hai_doan_lay_doan_dau() {
        String other = "22222222-2222-4222-a222-222222222222";

        TenantPath.Match match =
                TenantPath.organizationOf("/v1/organizations/" + ORG + "/links/organizations/" + other);

        assertThat(match.tenant()).isEqualTo(TenantId.parse(ORG));
    }
}
