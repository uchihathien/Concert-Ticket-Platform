// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Route cho các endpoint thống kê của ban tổ chức — CHỈ ngoài prod.
 *
 * <p>Khai bằng code thay vì thêm vào {@code application.yml} vì file đó dùng chung cho mọi môi
 * trường, và danh sách route trong YAML không gộp được giữa các profile. Với {@code @Profile("!prod")}
 * bản prod không có các route này và giữ nguyên hành vi cũ (các đường dẫn ấy rơi vào identity, 404).
 *
 * <p>{@code order(-1)}: các mẫu này hẹp hơn {@code /v1/organizations/**} của identity, nên phải được
 * xét TRƯỚC — route khai trong YAML đều có thứ tự 0.
 *
 * <p>Cái giá: route khai bằng code không đi qua {@code default-filters} của YAML, tức là không có
 * bộ giới hạn tần suất mặc định. Chấp nhận được ở dev; khi đưa lên prod thì chuyển các mẫu này vào
 * route {@code ordering}, {@code ticketing}, {@code inventory}, {@code catalog} trong YAML và xoá lớp này.
 */
@Configuration
@Profile("!prod")
public class OrganizationStatsRoutes {

    @Bean
    public RouteLocator organizationStatsRouteLocator(
            RouteLocatorBuilder builder,
            @Value("${ORDERING_URL:http://localhost:8093}") String orderingUrl,
            @Value("${TICKETING_URL:http://localhost:8097}") String ticketingUrl,
            @Value("${INVENTORY_URL:http://localhost:8092}") String inventoryUrl,
            @Value("${CATALOG_URL:http://localhost:8091}") String catalogUrl) {
        return builder.routes()
                .route("stats-orders", r -> r.order(-1)
                        .path("/v1/organizations/*/stats/orders/**")
                        .uri(orderingUrl))
                .route("stats-checkins", r -> r.order(-1)
                        .path("/v1/organizations/*/stats/checkins/**")
                        .uri(ticketingUrl))
                .route("stats-inventory", r -> r.order(-1)
                        .path("/v1/organizations/*/stats/inventory/**")
                        .uri(inventoryUrl))
                .route("stats-catalog", r -> r.order(-1)
                        .path("/v1/organizations/*/stats/catalog/**")
                        .uri(catalogUrl))
                .build();
    }
}
