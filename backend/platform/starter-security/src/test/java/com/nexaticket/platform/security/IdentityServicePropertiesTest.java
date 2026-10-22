// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link IdentityServiceProperties}: TTL của cache membership là một con số bảo mật — độ trễ tối
 * đa của việc thu hồi phiên và khoá tài khoản ở mọi service khác identity.
 */
class IdentityServicePropertiesTest {

    @Test
    @DisplayName("không khai gì: địa chỉ dev và TTL 60 giây — không bao giờ để trống thành 401 câm")
    void mac_dinh() {
        IdentityServiceProperties props = new IdentityServiceProperties(null, null);

        assertThat(props.baseUrl()).isEqualTo("http://localhost:8090");
        assertThat(props.membershipCacheTtl()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("baseUrl trống hoặc khoảng trắng: dùng mặc định")
    void base_url_trong() {
        assertThat(new IdentityServiceProperties("  ", null).baseUrl()).isEqualTo("http://localhost:8090");
    }

    @Test
    @DisplayName("TTL bằng 0 hoặc âm: về mặc định 60 giây")
    void ttl_khong_hop_le_ve_mac_dinh() {
        assertThat(new IdentityServiceProperties("http://x", Duration.ZERO).membershipCacheTtl())
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(new IdentityServiceProperties("http://x", Duration.ofSeconds(-5)).membershipCacheTtl())
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("TTL đúng 5 phút là trần còn chấp nhận")
    void ttl_dung_tran() {
        assertThat(new IdentityServiceProperties("http://x", Duration.ofMinutes(5)).membershipCacheTtl())
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("TTL quá 5 phút: từ chối khởi động — nhân viên bị cho nghỉ không được thao tác cả buổi")
    void ttl_qua_tran_bi_tu_choi() {
        assertThatThrownBy(() -> new IdentityServiceProperties(
                        "http://x", Duration.ofMinutes(5).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("membership-cache-ttl");
    }

    @Test
    @DisplayName("giá trị hợp lệ được giữ nguyên")
    void gia_tri_hop_le_giu_nguyen() {
        IdentityServiceProperties props =
                new IdentityServiceProperties("http://identity-service:8090", Duration.ofSeconds(10));

        assertThat(props.baseUrl()).isEqualTo("http://identity-service:8090");
        assertThat(props.membershipCacheTtl()).isEqualTo(Duration.ofSeconds(10));
    }
}
