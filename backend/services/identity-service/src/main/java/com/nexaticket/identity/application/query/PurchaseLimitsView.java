// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexaticket.identity.domain.port.PurchaseLimitsRepository;

/**
 * Trần mua vé của tổ chức, dạng DTO.
 *
 * <p>Tồn tại để kiểu của {@code domain.port} không rò ra hợp đồng HTTP: tầng interfaces chỉ được
 * chạm vào application (ArchitectureRules.hexagonalLayers). Nếu controller trả thẳng
 * {@code PurchaseLimitsRepository.Limits} thì hình dạng JSON công khai bị buộc vào một chi tiết
 * của tầng lưu trữ — đổi cổng lưu trữ là làm vỡ hợp đồng của frontend.
 *
 * <p>Mỗi trường {@code null} nghĩa là <b>kế thừa trần nền tảng</b>, không phải "không giới hạn".
 *
 * <p>{@code @JsonInclude(ALWAYS)} là bắt buộc ở đây, không phải trang trí. identity-service khai
 * {@code default-property-inclusion: non_null} cho toàn service — hợp lý với phần lớn phản hồi,
 * nhưng sai với DTO này: ở đây {@code null} MANG NGHĨA. Không có annotation này thì tổ chức chưa
 * khai trần nào sẽ nhận về {@code {}}, và frontend không phân biệt được "kế thừa mặc định" với
 * "endpoint trả thiếu trường" hay "API phiên bản cũ".
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PurchaseLimitsView(
        Integer maxSeatedPerHold, Integer maxStandingPerHold, Integer maxUnitsPerHold, Integer maxTicketsPerCustomer) {

    public static PurchaseLimitsView from(PurchaseLimitsRepository.Limits limits) {
        return new PurchaseLimitsView(
                limits.maxSeatedPerHold(),
                limits.maxStandingPerHold(),
                limits.maxUnitsPerHold(),
                limits.maxTicketsPerCustomer());
    }
}
