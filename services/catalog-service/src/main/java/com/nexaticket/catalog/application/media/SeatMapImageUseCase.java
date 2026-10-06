// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.media;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đặt ảnh sơ đồ khu vực ghế cho địa điểm hoặc cho một sự kiện.
 *
 * <h2>Vì sao dùng lại {@link PosterUrlPolicy} nguyên vẹn</h2>
 *
 * <p>Ảnh sơ đồ đi qua đúng cùng một đường với ảnh bìa: xin URL ký sẵn ở
 * {@code POST /uploads/poster}, trình duyệt tải thẳng lên kho vật thể, rồi lưu địa chỉ công khai
 * lại đây. Nên mọi thứ chính sách kia canh — https, vật thể có thật, đúng kiểu, đúng cỡ, không dán
 * đường dẫn host lạ khi production cấm — đều đúng nguyên văn cho ảnh này. Viết một chính sách thứ
 * hai gần giống là tạo ra một bản sao sẽ lệch đi sau lần sửa đầu tiên.
 *
 * <h2>{@code null} ở đây KHÁC {@code null} lúc sửa sự kiện</h2>
 *
 * <p>{@code PATCH /events/{id}} là phép patch: {@code null} nghĩa là "không nhắc tới, giữ nguyên".
 * Hai endpoint dưới đây thì không — chúng chỉ làm một việc, nên gọi tới tức là muốn đổi, và bỏ
 * trống nghĩa là <b>gỡ ảnh</b>. Nhập nhằng giữa hai ngữ nghĩa ấy chính là chỗ nút "gỡ ảnh" hay im
 * lặng không chạy.
 */
@Service
public class SeatMapImageUseCase {

    private final VenueRepository venues;
    private final EventRepository events;
    private final PosterUrlPolicy policy;
    private final CatalogAccess access;

    public SeatMapImageUseCase(
            VenueRepository venues, EventRepository events, PosterUrlPolicy policy, CatalogAccess access) {
        this.venues = venues;
        this.events = events;
        this.policy = policy;
        this.access = access;
    }

    @Transactional
    public void setForVenue(UUID organizationId, UUID venueId, String imageUrl) {
        access.requireCatalogManager(organizationId);
        String validated = validate(imageUrl);

        if (venues.updateSeatMapImage(organizationId, venueId, validated) == 0) {
            throw new ApiException(CatalogErrorCode.VENUE_NOT_FOUND, "Venue not found");
        }
    }

    @Transactional
    public void setForEvent(UUID organizationId, UUID eventId, String imageUrl) {
        access.requireCatalogManager(organizationId);
        String validated = validate(imageUrl);

        if (events.updateSeatMapImage(organizationId, eventId, validated) == 0) {
            throw new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found");
        }
    }

    /** Chuẩn hoá {@code null} về rỗng trước khi kiểm — xem ghi chú ở đầu lớp. */
    private String validate(String imageUrl) {
        return policy.validate(imageUrl == null ? "" : imageUrl);
    }
}
