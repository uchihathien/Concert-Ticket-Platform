// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.query.CheckinSessionQuery;
import com.nexaticket.catalog.application.query.PricingQuery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service của Catalog — chỉ service nội bộ gọi được.
 *
 * <p>Gateway cố ý không route {@code /internal/**} ra ngoài. Ở đây rủi ro thấp hơn các endpoint nội
 * bộ khác vì nó chỉ đọc và không đổi gì, nhưng nó vẫn lộ tỷ lệ hoa hồng nền tảng — con số mà
 * ADR-1010 quy định tổ chức <b>không</b> được thấy.
 *
 * <p>Ordering gọi endpoint này ở bước 3 của saga checkout, với hạn 2 giây. Nó phải rẻ: một câu đếm
 * có index và một lần đọc cấu hình, không join, không tính toán.
 */
@RestController
@RequestMapping("/internal/sessions")
public class InternalCatalogController {

    private final PricingQuery pricing;
    private final CheckinSessionQuery sessions;

    public InternalCatalogController(PricingQuery pricing, CheckinSessionQuery sessions) {
        this.pricing = pricing;
        this.sessions = sessions;
    }

    /** Tên field là hợp đồng với {@code CatalogHttpAdapter} của ticketing-service. */
    public record SessionResponse(
            UUID eventSessionId, UUID eventId, String eventTitle, Instant startsAt, Instant endsAt) {}

    /** Giờ diễn và tên sự kiện của một suất — ticketing-service hỏi khi soát vé. */
    @GetMapping("/{eventSessionId}")
    public SessionResponse session(@PathVariable UUID eventSessionId) {
        var info = sessions.info(eventSessionId);
        return new SessionResponse(
                info.eventSessionId(), info.eventId(), info.eventTitle(), info.startsAt(), info.endsAt());
    }

    /**
     * Mọi suất của một hoặc nhiều sự kiện ({@code ?eventId=a&eventId=b}) — ticketing-service hỏi để
     * dựng ca trực và để tính hạn tài khoản nhân viên soát vé. Tối đa 200 sự kiện một lần.
     */
    @GetMapping
    public List<SessionResponse> sessionsOfEvents(@RequestParam("eventId") List<UUID> eventIds) {
        if (eventIds.size() > 200) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Tối đa 200 sự kiện một lần");
        }
        return sessions.ofEvents(eventIds).stream()
                .map(info -> new SessionResponse(
                        info.eventSessionId(), info.eventId(), info.eventTitle(), info.startsAt(), info.endsAt()))
                .toList();
    }

    /**
     * Hình dạng JSON là <b>hợp đồng với ordering-service</b>, viết ra tường minh thay vì trả thẳng
     * record của tầng application. Đổi tên field ở đây là làm hỏng checkout ở runtime, và không có
     * gì lúc biên dịch báo cho biết.
     */
    public record PricingResponse(int commissionBps, long discountVnd) {}

    /**
     * @param promotionCode có thể vắng mặt hoặc rỗng. Chuỗi rỗng được coi như không nhập gì:
     *     {@code UriComponentsBuilder} bên gọi vẫn gắn tên tham số vào URL khi giá trị là null, nên
     *     nếu ở đây phân biệt "rỗng" với "vắng mặt" thì mọi đơn không dùng khuyến mãi đều bị từ chối.
     * @param subtotalVnd tạm tính do Ordering cộng từ giá ghế; chỉ dùng để tính giảm giá theo phần
     *     trăm sau này, KHÔNG dùng để tính tiền phải trả — tiền do Ordering chốt từ dữ liệu của
     *     Inventory
     */
    @GetMapping("/{eventSessionId}/pricing")
    public PricingResponse pricing(
            @PathVariable UUID eventSessionId,
            @RequestParam(required = false) String promotionCode,
            @RequestParam(defaultValue = "0") long subtotalVnd) {

        PricingQuery.Pricing resolved = pricing.resolve(eventSessionId, promotionCode, subtotalVnd);
        return new PricingResponse(resolved.commissionBps(), resolved.discountVnd());
    }
}
