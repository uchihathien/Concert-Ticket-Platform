// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Định danh sự kiện trên URL công khai, unique toàn hệ thống.
 *
 * <p>Là bản sao của {@code identity.domain.model.Slug} chứ không dùng chung: hai context được đổi
 * luật riêng (độ dài, ký tự cho phép) mà không kéo nhau. Chép một record ba mươi dòng rẻ hơn nhiều
 * so với một shared kernel mà mọi thay đổi đều phải hỏi ý cả hai bên.
 */
public record Slug(String value) {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    public Slug {
        if (value == null || !VALID.matcher(value).matches() || value.length() > 96) {
            throw new IllegalArgumentException("Slug không hợp lệ: " + value);
        }
    }

    /** Sinh slug từ tên tiếng Việt: "Đêm nhạc Mùa Thu" -> "dem-nhac-mua-thu". */
    public static Slug from(String title) {
        String ascii = Normalizer.normalize(title, Normalizer.Form.NFD)
                .replace('Đ', 'D')
                .replace('đ', 'd');
        ascii = DIACRITICS.matcher(ascii).replaceAll("");
        String slug = NON_ALNUM
                .matcher(ascii.toLowerCase(Locale.ROOT))
                .replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("Không sinh được slug từ: " + title);
        }
        return new Slug(slug.length() > 96 ? slug.substring(0, 96).replaceAll("-+$", "") : slug);
    }

    /** Hậu tố khi trùng: "dem-nhac-mua-thu" -> "dem-nhac-mua-thu-2". */
    public Slug withSuffix(int n) {
        return new Slug(value + "-" + n);
    }

    @Override
    public String toString() {
        return value;
    }
}
