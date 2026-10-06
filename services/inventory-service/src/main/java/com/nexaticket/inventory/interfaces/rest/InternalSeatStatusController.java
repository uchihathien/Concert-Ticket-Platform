// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.interfaces.rest;

import com.nexaticket.inventory.application.query.SeatQueries;
import com.nexaticket.inventory.application.query.SeatStatusView;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service đọc tồn kho — chỉ service nội bộ gọi được.
 *
 * <p>catalog-service gọi endpoint này để dựng master data cho bảng điều khiển của ban tổ chức. Chỉ
 * đọc và chỉ trả về số đếm, nên rủi ro thấp hơn {@code /internal/reservations} — nhưng nó vẫn nằm
 * dưới {@code /internal/**} vì tồn kho theo zone của một suất chưa mở bán là thông tin thương mại
 * của tổ chức, không phải dữ liệu công khai.
 *
 * <p><b>404 là một câu trả lời, không phải lỗi.</b> Suất chưa được dựng tồn kho — sự kiện còn nháp
 * — trả 404, và bên gọi hiểu đó là "chưa lên bán". Trả 200 với danh sách rỗng sẽ khiến hai tình
 * huống khác hẳn nhau trông giống nhau: chưa publish, và publish rồi nhưng bán hết sạch.
 */
@RestController
@RequestMapping("/internal/sessions")
public class InternalSeatStatusController {

    private final SeatQueries queries;

    public InternalSeatStatusController(SeatQueries queries) {
        this.queries = queries;
    }

    /**
     * Hình dạng JSON là <b>hợp đồng với catalog-service</b>, viết ra tường minh thay vì trả thẳng
     * record của tầng application.
     *
     * <p>Tên field ở đây phải khớp {@code InventorySeatStatusAdapter.SeatStatusResponse}. Lệch tên
     * thì bên kia đọc ra {@code null} và bảng điều khiển hiện 0 chỗ cho một suất đã bán hết — không
     * có gì lúc biên dịch báo cho biết.
     */
    public record SeatStatusResponse(UUID eventSessionId, long availabilityVersion, List<ZoneResponse> zones) {

        public record ZoneResponse(
                String zoneCode, String admissionType, int available, int held, int reserved, int sold, int blocked) {}

        static SeatStatusResponse from(SeatStatusView view) {
            return new SeatStatusResponse(
                    view.eventSessionId(),
                    view.availabilityVersion(),
                    view.zones().stream()
                            .map(z -> new ZoneResponse(
                                    z.zoneCode(),
                                    z.admissionType(),
                                    z.available(),
                                    z.held(),
                                    z.reserved(),
                                    z.sold(),
                                    z.blocked()))
                            .toList());
        }
    }

    @GetMapping("/{eventSessionId}/seat-status")
    public SeatStatusResponse seatStatus(@PathVariable UUID eventSessionId) {
        return SeatStatusResponse.from(queries.seatStatus(eventSessionId));
    }
}
