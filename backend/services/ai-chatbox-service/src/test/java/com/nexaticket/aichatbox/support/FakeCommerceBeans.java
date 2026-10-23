// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Thay bốn adapter HTTP sang catalog/inventory/ordering/identity bằng hàng giả trong một context
 * Spring thật — cho IT cần cả Postgres lẫn luồng đặt vé mà không cần ba service kia chạy.
 *
 * <p>Cùng mẫu với {@link FakeAiProviders}: {@code @Primary} thắng adapter thật khi inject theo port.
 * Mỗi test tự nạp dữ liệu vào bean (autowire kiểu cụ thể), và vì bean là singleton của context, test
 * phải dùng id ngẫu nhiên thay vì dựa vào trạng thái sạch.
 */
@TestConfiguration
public class FakeCommerceBeans {

    @Bean
    @Primary
    public FakeCommerce.Catalog fakeCatalog() {
        return new FakeCommerce.Catalog();
    }

    @Bean
    @Primary
    public FakeCommerce.Inventory fakeInventory() {
        return new FakeCommerce.Inventory();
    }

    @Bean
    @Primary
    public FakeCommerce.Ordering fakeOrdering() {
        return new FakeCommerce.Ordering();
    }

    @Bean
    @Primary
    public FakeCommerce.Credentials fakeCredentials() {
        return new FakeCommerce.Credentials();
    }
}
