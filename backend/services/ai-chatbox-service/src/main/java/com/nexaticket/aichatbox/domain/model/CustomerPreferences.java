// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sở thích mua vé khách tự nói với trợ lý.
 *
 * @param categories mã nhóm của catalog ({@code nhac-song}, …), thứ tự khách nói
 * @param cities thành phố, mới nhất đứng đầu
 * @param maxBudgetVnd giá vé tối đa khách muốn trả cho một vé; null khi chưa nói
 * @param note một câu tự do, null khi không có
 */
public record CustomerPreferences(
        UUID userId, List<String> categories, List<String> cities, Long maxBudgetVnd, String note, Instant updatedAt) {

    public CustomerPreferences {
        categories = List.copyOf(categories);
        cities = List.copyOf(cities);
    }
}
