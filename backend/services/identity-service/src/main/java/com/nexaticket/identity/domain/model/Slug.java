// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Định danh thân thiện của tổ chức, unique toàn hệ thống. */
public record Slug(String value) {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    public Slug {
        if (value == null || !VALID.matcher(value).matches() || value.length() > 64) {
            throw new IllegalArgumentException("Slug không hợp lệ: " + value);
        }
    }

    /** Sinh slug từ tên tiếng Việt: "Nhà hát Lớn Hà Nội" -> "nha-hat-lon-ha-noi". */
    public static Slug from(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replace('\u0110', 'D')
                .replace('\u0111', 'd');
        ascii = DIACRITICS.matcher(ascii).replaceAll("");
        String slug = NON_ALNUM
                .matcher(ascii.toLowerCase(Locale.ROOT))
                .replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("Không sinh được slug từ: " + name);
        }
        return new Slug(slug.length() > 64 ? slug.substring(0, 64).replaceAll("-+$", "") : slug);
    }

    /** Hậu tố khi trùng: "nha-hat-lon" -> "nha-hat-lon-2". */
    public Slug withSuffix(int n) {
        return new Slug(value + "-" + n);
    }

    @Override
    public String toString() {
        return value;
    }
}
