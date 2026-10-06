// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.nexaticket.aichatbox.domain.model.CustomerProfile;
import com.nexaticket.aichatbox.domain.model.EventBrief;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.EventRules;
import com.nexaticket.aichatbox.domain.model.KnowledgeChunk;
import com.nexaticket.aichatbox.domain.model.KnowledgeEntry;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.model.RefundPolicy;
import com.nexaticket.aichatbox.domain.model.RulesEntry;
import com.nexaticket.aichatbox.domain.model.TicketHold;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.CallerCredentialsPort;
import com.nexaticket.aichatbox.domain.port.CatalogClientPort;
import com.nexaticket.aichatbox.domain.port.CustomerProfilePort;
import com.nexaticket.aichatbox.domain.port.InventoryClientPort;
import com.nexaticket.aichatbox.domain.port.OrderNotFoundException;
import com.nexaticket.aichatbox.domain.port.OrderingClientPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.domain.port.VectorStorePort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Các cổng ra ngoài ở dạng giả, điều khiển được từng lời gọi — cho unit test của tool và use case.
 *
 * <p>Không dùng mocking framework, cùng lý do với {@link FakeAiProviders}: "lần sau trả về cái
 * này" và "đã gọi với tham số gì" đọc rõ hơn ở một lớp thường.
 */
public final class FakeCommerce {

    private FakeCommerce() {}

    public static final String TOKEN = "token-cua-khach";

    /** Token cố định — use case chỉ cần chuyển tiếp nó, không đọc. */
    public static class Credentials implements CallerCredentialsPort {
        @Override
        public String currentAccessToken() {
            return TOKEN;
        }
    }

    public static class Ordering implements OrderingClientPort {

        public final Map<UUID, OrderSummary> orders = new HashMap<>();
        public final List<OrderSummary> mine = new ArrayList<>();
        public final List<String> placeKeys = new ArrayList<>();
        public final List<UUID> placedHolds = new ArrayList<>();
        public PlacedOrder nextOrder;
        public RuntimeException placeFailure;
        public boolean down;

        public OrderSummary put(OrderSummary order) {
            orders.put(order.orderId(), order);
            return order;
        }

        @Override
        public OrderSummary fetchOrder(UUID orderId, String callerAccessToken) {
            if (down) {
                throw new RemoteCallException("ordering-service", "giả: đang hỏng", null);
            }
            OrderSummary order = orders.get(orderId);
            if (order == null) {
                throw new OrderNotFoundException(orderId);
            }
            return order;
        }

        @Override
        public List<OrderSummary> listMyOrders(String callerAccessToken, int limit) {
            if (down) {
                throw new RemoteCallException("ordering-service", "giả: đang hỏng", null);
            }
            return mine.stream().limit(limit).toList();
        }

        @Override
        public PlacedOrder placeOrder(UUID holdId, String callerAccessToken, String idempotencyKey) {
            placeKeys.add(idempotencyKey);
            placedHolds.add(holdId);
            if (placeFailure != null) {
                throw placeFailure;
            }
            return nextOrder;
        }
    }

    public static class Catalog implements CatalogClientPort {

        public final Map<String, EventDetail> events = new HashMap<>();
        /** Khoá {@code slug|zoneCode}. */
        public final Map<String, ZoneAdmission> zones = new HashMap<>();

        public void put(EventDetail event, ZoneAdmission admissionOfEveryZone) {
            events.put(event.slug(), event);
            for (EventDetail.Session session : event.sessions()) {
                for (EventDetail.Tier tier : session.tiers()) {
                    zones.put(event.slug() + "|" + tier.zoneCode(), admissionOfEveryZone);
                }
            }
        }

        @Override
        public List<EventBrief> searchEvents(String query, String city, String category, int limit) {
            return events.values().stream()
                    .map(e -> new EventBrief(
                            e.slug(),
                            e.title(),
                            e.category(),
                            e.city(),
                            e.venueName(),
                            null,
                            null,
                            e.sessions().size()))
                    .toList();
        }

        @Override
        public Optional<EventDetail> findEventBySlug(String slug) {
            return Optional.ofNullable(events.get(slug));
        }

        @Override
        public Optional<ZoneAdmission> findZoneAdmission(String slug, String zoneCode) {
            return Optional.ofNullable(zones.get(slug + "|" + zoneCode));
        }
    }

    public static class Inventory implements InventoryClientPort {

        public final List<String> holdKeys = new ArrayList<>();
        public final List<ZoneAdmission> admissions = new ArrayList<>();
        public final List<UUID> released = new ArrayList<>();
        public RuntimeException holdFailure;
        public Instant holdExpiresAt = Instant.now().plus(Duration.ofMinutes(10));

        @Override
        public TicketHold holdZone(
                UUID eventSessionId,
                String zoneCode,
                int quantity,
                ZoneAdmission admission,
                String callerAccessToken,
                String idempotencyKey) {
            holdKeys.add(idempotencyKey);
            admissions.add(admission);
            if (holdFailure != null) {
                throw holdFailure;
            }
            return new TicketHold(UUID.randomUUID(), holdExpiresAt, quantity);
        }

        @Override
        public void releaseHold(UUID holdId, String callerAccessToken) {
            released.add(holdId);
        }
    }

    public static class Profiles implements CustomerProfilePort {

        public CustomerProfile profile = new CustomerProfile("Lữ Đình Thiện", "thien.lu@gmail.com", "0912345678");
        public boolean down;

        @Override
        public CustomerProfile currentProfile(String callerAccessToken) {
            if (down) {
                throw new RemoteCallException("identity-service", "giả: đang hỏng", null);
            }
            return profile;
        }
    }

    /** Kho tri thức giả: chỉ có quy định sự kiện; tìm ngữ nghĩa luôn rỗng. */
    public static class Knowledge implements VectorStorePort {

        public final Map<UUID, EventRules> rules = new HashMap<>();

        public void publishRules(UUID eventId, String title, RefundPolicy policy) {
            rules.put(eventId, new EventRules(eventId, title, "Quy định " + title, policy));
        }

        @Override
        public List<KnowledgeChunk> searchSimilar(float[] queryEmbedding, UUID eventId, int topK) {
            return List.of();
        }

        @Override
        public Optional<EventRules> findRules(UUID eventId) {
            return Optional.ofNullable(rules.get(eventId));
        }

        @Override
        public UUID addChunk(UUID eventId, String title, String content, float[] embedding) {
            return UUID.randomUUID();
        }

        @Override
        public boolean deleteChunk(UUID id) {
            return false;
        }

        @Override
        public List<KnowledgeEntry> listChunks(UUID eventId, int limit, int offset) {
            return List.of();
        }

        @Override
        public void upsertRules(
                UUID eventId, String eventTitle, String content, RefundPolicy refundPolicy, boolean published) {
            if (published) {
                rules.put(eventId, new EventRules(eventId, eventTitle, content, refundPolicy));
            }
        }

        @Override
        public Optional<RulesEntry> findRulesForCurator(UUID eventId) {
            return findRules(eventId)
                    .map(r -> new RulesEntry(
                            r.eventId(), r.eventTitle(), r.content(), r.refundPolicy(), true, Instant.now()));
        }
    }

    // --- Dữ liệu mẫu --------------------------------------------------------

    public static OrderSummary paidOrder(UUID eventId, Instant paidAt) {
        return new OrderSummary(
                UUID.randomUUID(),
                "NT-240001",
                eventId,
                UUID.randomUUID(),
                "PAID",
                1_800_000L,
                null,
                paidAt,
                List.of(new OrderSummary.Item("Khán đài A · ghế 20", 1_800_000L)));
    }

    public static OrderSummary unpaidOrder(UUID eventId) {
        return new OrderSummary(
                UUID.randomUUID(),
                "NT-240002",
                eventId,
                UUID.randomUUID(),
                "AWAITING_PAYMENT",
                450_000L,
                Instant.now().plus(Duration.ofMinutes(15)),
                null,
                List.of(new OrderSummary.Item("Khu đứng · vé đứng", 450_000L)));
    }

    /** Một sự kiện một suất, hai hạng vé: khu A ngồi, khu GA đứng. */
    public static EventDetail sampleEvent(UUID sessionId) {
        return new EventDetail(
                "dem-nhac-trinh",
                "Đêm nhạc Trịnh",
                null,
                null,
                "nhac-song",
                "Hà Nội",
                "Nhà hát Lớn",
                "1 Tràng Tiền",
                List.of(new EventDetail.Session(
                        sessionId,
                        Instant.parse("2026-11-20T13:00:00Z"),
                        Instant.parse("2026-11-20T15:00:00Z"),
                        Instant.parse("2026-11-20T12:00:00Z"),
                        List.of(
                                new EventDetail.Tier("VIP", 1_800_000L, "A", "Khán đài A"),
                                new EventDetail.Tier("Phổ thông", 450_000L, "GA", "Khu đứng")))));
    }
}
