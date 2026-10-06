// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import com.nexaticket.catalog.domain.model.Slug;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.platform.web.error.ApiException;
import org.springframework.stereotype.Component;

/**
 * Chọn slug cho một sự kiện mới.
 *
 * <p>Tách ra khỏi {@code CreateEventHandler} khi có đường tạo sự kiện thứ hai (từ khung concert).
 * Hai đường phải sinh slug <b>giống hệt nhau</b>: slug nằm trên URL công khai và unique toàn hệ
 * thống, nên hai luật khác nhau nghĩa là một trong hai đường tạo ra link mà người dùng không đoán
 * được — và không ai so sánh hai handler để phát hiện.
 */
@Component
public class SlugAllocator {

    /** Đủ để vượt qua trùng lặp thật; nhiều hơn nữa thì slug đã hết là tên và thành mã số. */
    private static final int MAX_SLUG_ATTEMPTS = 20;

    private final EventRepository events;

    public SlugAllocator(EventRepository events) {
        this.events = events;
    }

    /**
     * Slug do người dùng chọn thì phải đúng như đã chọn; slug sinh từ tiêu đề thì được thêm hậu tố.
     *
     * <p>Phân biệt này quan trọng: người gõ tay "hoa-am-2026" mà nhận về "hoa-am-2026-3" sẽ không
     * hiểu chuyện gì xảy ra và sẽ dán nhầm link. Còn người không quan tâm tới slug thì cũng không
     * quan tâm tới hậu tố.
     *
     * <p>Vòng lặp này là tiện lợi, không phải chốt chặn — hai request đồng thời vẫn có thể cùng
     * thấy slug trống. Chốt chặn thật là ràng buộc UNIQUE của database.
     */
    public Slug allocate(String requested, String title) {
        if (requested != null && !requested.isBlank()) {
            Slug slug = new Slug(requested.trim());
            if (events.slugExists(slug)) {
                throw new ApiException(CatalogErrorCode.SLUG_ALREADY_TAKEN, "Slug đã có người dùng: " + slug);
            }
            return slug;
        }

        Slug base = Slug.from(title);
        Slug candidate = base;
        for (int n = 2; events.slugExists(candidate); n++) {
            if (n > MAX_SLUG_ATTEMPTS) {
                throw new ApiException(CatalogErrorCode.SLUG_ALREADY_TAKEN, "Không sinh được slug trống từ: " + title);
            }
            candidate = base.withSuffix(n);
        }
        return candidate;
    }
}
