// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.identity.domain.model.Slug;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Slug của tổ chức: định danh trên URL, unique toàn hệ thống.
 *
 * <p>Slug do superadmin gõ tay đi thẳng vào constructor, nên constructor phải là chốt chặn duy nhất
 * cho mọi ký tự lạ — không có tầng nào phía sau kiểm lại.
 */
class SlugTest {

    @Nested
    @DisplayName("slug gõ tay")
    class GoTay {

        @ParameterizedTest
        @ValueSource(strings = {"a", "nha-hat", "abc-123-xyz", "2026"})
        @DisplayName("chữ thường, số và gạch nối đơn được chấp nhận")
        void hop_le(String value) {
            assertThat(new Slug(value).value()).isEqualTo(value);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(
                strings = {
                    "Nha-Hat",
                    "nha hat",
                    "-nha",
                    "nha-",
                    "nha--hat",
                    "nhà-hát",
                    "../admin",
                    "nha_hat",
                    "nha/hat",
                    "nha%2Fhat"
                })
        @DisplayName("chữ hoa, khoảng trắng, gạch nối đầu/cuối/kép, dấu và ký tự đường dẫn bị từ chối")
        void khong_hop_le(String value) {
            assertThatThrownBy(() -> new Slug(value)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("đúng 64 ký tự thì được, 65 thì không")
        void bien_do_dai() {
            assertThat(new Slug("a".repeat(64)).value()).hasSize(64);
            assertThatThrownBy(() -> new Slug("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("slug sinh từ tên")
    class SinhTuTen {

        @Test
        @DisplayName("ký tự đặc biệt liên tiếp gộp thành một gạch nối, đầu cuối được cắt")
        void gop_ky_tu_dac_biet() {
            assertThat(Slug.from("  !!Đêm   nhạc — Hà Nội!! ").value()).isEqualTo("dem-nhac-ha-noi");
        }

        @Test
        @DisplayName("tên quá dài bị cắt về 64 ký tự mà không để gạch nối ở cuối")
        void cat_ten_dai() {
            // 63 ký tự 'a' rồi một khoảng trắng: cắt ở 64 sẽ rơi đúng vào gạch nối, và slug kết thúc
            // bằng '-' là slug constructor từ chối — tức là tạo tổ chức hỏng bằng một lỗi 500.
            Slug slug = Slug.from("a".repeat(63) + " bcdef");

            assertThat(slug.value()).hasSizeLessThanOrEqualTo(64).doesNotEndWith("-");
        }

        @ParameterizedTest
        @ValueSource(strings = {"!!!", "   ", "——", "😀😀"})
        @DisplayName("tên không còn chữ hay số nào thì không sinh được slug")
        void ten_khong_co_chu(String name) {
            assertThatThrownBy(() -> Slug.from(name))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Không sinh được slug");
        }
    }

    @Test
    @DisplayName("hậu tố khi trùng nối bằng gạch nối và vẫn là slug hợp lệ")
    void hau_to() {
        assertThat(new Slug("nha-hat").withSuffix(2).value()).isEqualTo("nha-hat-2");
    }

    @Test
    @DisplayName("hậu tố làm slug vượt 64 ký tự thì bị từ chối thay vì lưu một slug dài")
    void hau_to_vuot_do_dai() {
        assertThatThrownBy(() -> new Slug("a".repeat(63)).withSuffix(10)).isInstanceOf(IllegalArgumentException.class);
    }
}
