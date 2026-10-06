// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import com.nexaticket.aichatbox.domain.model.EventBrief;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.CatalogClientPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * Anti-Corruption Layer sang catalog-service.
 *
 * <p>Gọi đường công khai {@code GET /v1/events} và {@code GET /v1/events/{slug}} — lý do đầy đủ ở
 * {@link CatalogClientPort}. Không kiểu dữ liệu nào của Catalog đi quá lớp này: Catalog đổi tên
 * field thì chỗ duy nhất phải sửa là {@link EventPageResponse} và {@link EventDetailResponse}.
 */
@Component
public class CatalogHttpAdapter implements CatalogClientPort {

    private static final String SERVICE = "catalog-service";

    /**
     * Trần cứng cho số kết quả, bất kể mô hình xin bao nhiêu.
     *
     * <p>Mô hình có thể đặt {@code limit} tuỳ ý, và "cho tôi tất cả sự kiện" là câu nó rất dễ dịch
     * thành một con số lớn. Mỗi dòng trả về là token phải trả tiền ở vòng sau, và trên mô hình chạy
     * tại chỗ thì một danh sách dài còn kéo lượt chat vượt hạn của nhà cung cấp — khách nhận 503
     * thay vì một câu trả lời ngắn.
     */
    private static final int MAX_RESULTS = 8;

    private final RestClient client;

    public CatalogHttpAdapter(@Qualifier("catalogClient") RestClient client) {
        this.client = client;
    }

    /**
     * Tìm, và nếu không ra gì thì tìm lại bằng từ dài nhất trong câu.
     *
     * <h3>Vì sao cần lần thử thứ hai</h3>
     *
     * Bộ lọc {@code query} của Catalog so khớp <b>chuỗi con</b> của tên sự kiện. Tên thật thường có
     * dấu phân cách mà không ai gõ lại: "Kịch nói — Người Ở Lại", "Đêm nhạc Trịnh · Ru Đời Đi Nhé".
     * Khách gõ "Kịch nói Người Ở Lại" — một chuỗi KHÔNG phải chuỗi con của tên ấy vì thiếu dấu gạch
     * — và kết quả là rỗng. Đo được lỗi thật: trợ lý trả lời "NexaTicket chưa bán vé cho sự kiện
     * này" về một sự kiện đang bán vé, nghe rất dứt khoát.
     *
     * <p>Mô tả tool đã bảo mô hình "thử lại với ít chữ hơn" và nó KHÔNG làm — nó trả lời luôn. Lời
     * nhắc trong prompt là lời nhờ; chỗ này cần một hành vi chắc chắn, nên lần thử thứ hai nằm ở
     * đây. Một lời gọi thêm vào read model của Catalog là vài mili-giây, rẻ hơn hẳn một câu trả lời
     * sai.
     *
     * <p>Cách sửa gốc hơn là cho Catalog tìm theo TỪ (AND các từ) thay vì theo chuỗi con — nhưng đó
     * là bộ lọc dùng chung với ô tìm kiếm của trang khách, nên đổi nó là đổi hành vi của cả hai.
     */
    @Override
    public List<EventBrief> searchEvents(String query, String city, String category, int limit) {
        List<EventBrief> found = searchOnce(query, city, category, limit);
        if (!found.isEmpty()) {
            return found;
        }
        String fallback = longestWord(query);
        return fallback == null ? found : searchOnce(fallback, city, category, limit);
    }

    /** Từ dài nhất, tính theo ký tự — bỏ qua từ ngắn vì "nói", "và", "ở" khớp với mọi thứ. */
    private static String longestWord(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String best = null;
        for (String word : query.trim().split("[\\s\\p{Punct}—·]+")) {
            if (word.length() >= 4 && (best == null || word.length() > best.length())) {
                best = word;
            }
        }
        // Chỉ đáng thử lại khi nó THẬT SỰ ngắn hơn câu gốc; bằng nhau thì lần thử hai cho ra cùng
        // một kết quả rỗng và chỉ tốn thêm một lời gọi.
        return best == null || best.equals(query.trim()) ? null : best;
    }

    private List<EventBrief> searchOnce(String query, String city, String category, int limit) {
        int size = Math.clamp(limit, 1, MAX_RESULTS);
        try {
            EventPageResponse page = client.get()
                    .uri(uri -> buildSearchUri(uri, query, city, category, size))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new RemoteCallException(SERVICE, "Catalog trả " + status, null);
                        }
                        return response.bodyTo(EventPageResponse.class);
                    });
            return page == null || page.items() == null
                    ? List.of()
                    : page.items().stream().map(EventCardResponse::toDomain).toList();
        } catch (RemoteCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RemoteCallException(SERVICE, "Không gọi được catalog-service", e);
        }
    }

    @Override
    public Optional<EventDetail> findEventBySlug(String slug) {
        try {
            return client.get().uri("/v1/events/{slug}", slug).exchange((request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                // 404 = slug không có thật. Mô hình thường tự dựng slug từ tên sự kiện và
                // đoán sai, nên đây là đường đi BÌNH THƯỜNG, không phải sự cố — trả rỗng để
                // dispatcher nói với mô hình rằng hãy tìm theo tên trước.
                if (status.value() == 404) {
                    return Optional.<EventDetail>empty();
                }
                if (!status.is2xxSuccessful()) {
                    throw new RemoteCallException(SERVICE, "Catalog trả " + status, null);
                }
                EventDetailResponse body = response.bodyTo(EventDetailResponse.class);
                return Optional.ofNullable(body).map(EventDetailResponse::toDomain);
            });
        } catch (RemoteCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RemoteCallException(SERVICE, "Không gọi được catalog-service", e);
        }
    }

    /**
     * Loại khu, đọc từ sơ đồ công khai {@code GET /v1/events/{slug}/floor-plan}.
     *
     * <p>Sơ đồ công khai không kèm danh sách ghế (catalog cố ý bỏ để nhẹ), nhưng có {@code kind}
     * của từng khu — đúng thứ cần. {@code kind} là tên enum của catalog ({@code SEATED} /
     * {@code STANDING}); tên lạ thì coi như không biết, và tool giữ chỗ sẽ mời khách sang trang web.
     */
    @Override
    public Optional<ZoneAdmission> findZoneAdmission(String slug, String zoneCode) {
        try {
            FloorPlanResponse plan = client.get()
                    .uri("/v1/events/{slug}/floor-plan", slug)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 404) {
                            return null;
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new RemoteCallException(SERVICE, "Catalog trả " + status, null);
                        }
                        return response.bodyTo(FloorPlanResponse.class);
                    });
            if (plan == null || plan.zones() == null) {
                return Optional.empty();
            }
            return plan.zones().stream()
                    .filter(zone -> zone.zoneCode() != null && zone.zoneCode().equalsIgnoreCase(zoneCode))
                    .findFirst()
                    .flatMap(zone -> {
                        if ("SEATED".equalsIgnoreCase(zone.kind())) {
                            return Optional.of(ZoneAdmission.SEATED);
                        }
                        if ("STANDING".equalsIgnoreCase(zone.kind())) {
                            return Optional.of(ZoneAdmission.STANDING);
                        }
                        return Optional.empty();
                    });
        } catch (RemoteCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RemoteCallException(SERVICE, "Không gọi được catalog-service", e);
        }
    }

    /**
     * Tham số rỗng thì <b>không gửi</b>, chứ không gửi chuỗi rỗng.
     *
     * <p>{@code query=} (rỗng) và không có {@code query} là hai chuyện khác nhau với bộ lọc của
     * Catalog, và mô hình rất hay điền chuỗi rỗng cho tham số nó không biết.
     */
    private static java.net.URI buildSearchUri(UriBuilder uri, String query, String city, String category, int size) {
        uri.path("/v1/events").queryParam("page", 0).queryParam("size", size);
        if (notBlank(query)) {
            uri.queryParam("query", query.trim());
        }
        if (notBlank(city)) {
            uri.queryParam("city", city.trim());
        }
        if (notBlank(category)) {
            uri.queryParam("category", category.trim());
        }
        return uri.build();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record EventPageResponse(List<EventCardResponse> items) {}

    private record EventCardResponse(
            String slug,
            String title,
            String category,
            String city,
            String venueName,
            Instant nextSessionAt,
            Long fromPriceVnd,
            int sessionCount) {

        EventBrief toDomain() {
            return new EventBrief(slug, title, category, city, venueName, nextSessionAt, fromPriceVnd, sessionCount);
        }
    }

    /**
     * Chỉ những trường trả lời được câu hỏi của khách.
     *
     * <p>Cố tình <b>không</b> khai {@code posterUrl} và {@code floorPlan}: mô hình không mô tả được
     * ảnh, và thứ không đi vào tiến trình thì không thể lọt vào prompt rồi thành token phải trả tiền.
     */
    private record EventDetailResponse(
            String slug,
            String title,
            String summary,
            String description,
            String category,
            String city,
            String venueName,
            String venueAddress,
            List<SessionResponse> sessions) {

        private record SessionResponse(
                UUID id, Instant startsAt, Instant endsAt, Instant salesCloseAt, List<TierResponse> tiers) {}

        private record TierResponse(String name, long priceVnd, String zoneCode, String zoneName) {}

        EventDetail toDomain() {
            return new EventDetail(
                    slug,
                    title,
                    summary,
                    description,
                    category,
                    city,
                    venueName,
                    venueAddress,
                    sessions == null
                            ? List.of()
                            : sessions.stream()
                                    .map(s -> new EventDetail.Session(
                                            s.id(),
                                            s.startsAt(),
                                            s.endsAt(),
                                            s.salesCloseAt(),
                                            s.tiers() == null
                                                    ? List.of()
                                                    : s.tiers().stream()
                                                            .map(t -> new EventDetail.Tier(
                                                                    t.name(), t.priceVnd(), t.zoneCode(), t.zoneName()))
                                                            .toList()))
                                    .toList());
        }
    }

    /** Chỉ hai trường của mỗi khu — phần còn lại của sơ đồ (toạ độ, đa giác bao) là của màn hình. */
    private record FloorPlanResponse(List<ZoneResponse> zones) {}

    private record ZoneResponse(String zoneCode, String kind) {}
}
