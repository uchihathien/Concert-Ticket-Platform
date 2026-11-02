// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.query.CatalogViews;
import com.nexaticket.catalog.application.query.CheckinSessionQuery;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lịch diễn theo sự kiện — Open Host Service cho ticketing-service.
 *
 * <p>Ticketing cần nó ở hai chỗ: kiểm sự kiện được gán có đúng của tổ chức không, và tính hạn của
 * phân công soát vé (suất cuối kết thúc + 6 giờ). Gateway không route {@code /internal/**} ra ngoài.
 */
@RestController
@RequestMapping("/internal/events")
public class InternalEventScheduleController {

    /** Một bảng phân công của cả tổ chức hiếm khi vượt vài chục sự kiện; chặn một URL vô hạn. */
    private static final int MAX_EVENTS = 500;

    private final CheckinSessionQuery sessions;

    public InternalEventScheduleController(CheckinSessionQuery sessions) {
        this.sessions = sessions;
    }

    /** Tên field là hợp đồng với {@code CatalogHttpAdapter} của ticketing-service. */
    @GetMapping("/schedules")
    public List<CatalogViews.EventSchedule> schedules(@RequestParam List<UUID> eventIds) {
        if (eventIds.size() > MAX_EVENTS) {
            throw new com.nexaticket.platform.web.error.ApiException(
                    com.nexaticket.platform.web.error.ErrorCode.Common.VALIDATION_FAILED,
                    "Tối đa " + MAX_EVENTS + " sự kiện mỗi lần");
        }
        return sessions.schedules(eventIds);
    }
}
