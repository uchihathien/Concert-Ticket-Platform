// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.seed;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bán sẵn một phần chỗ của các suất diễn mẫu.
 *
 * <p>Một sơ đồ chỗ mà mọi ghế đều trống là sơ đồ chỗ chưa bao giờ được nhìn nghiêm túc: màu "đã
 * bán" không ai thấy, khoảng trống giữa hai nhóm ghế không tồn tại, và câu hỏi thật sự khó của màn
 * hình đó — "còn hai ghế cạnh nhau ở đâu?" — không có gì để trả lời. Bộ này lấp chỗ trống đó.
 *
 * <p><b>Bán theo cụm liền kề, không rải đều.</b> Người ta mua vé theo nhóm hai tới bốn người ngồi
 * cạnh nhau, nên một sơ đồ chỗ thật gồm những mảng liền và những ghế lẻ kẹt giữa. Rải ngẫu nhiên
 * từng ghế cho ra một bức ảnh nhiễu không giống bất cứ khán phòng nào, và tệ hơn: nó khiến "còn
 * hai ghế cạnh nhau" luôn đúng, giấu mất đúng cái trường hợp khó.
 *
 * <p><b>Chỉ đụng tới tổ chức mẫu.</b> Hai lớp chặn: cờ {@code demo.occupancy} và so khớp
 * {@code organization_id}. Chỉ có cờ thôi là chưa đủ — một lần bật nhầm ở môi trường thật sẽ khoá
 * hàng nghìn chỗ đang bán được, và không có đường lùi nào ngoài việc dựng lại tồn kho.
 *
 * <p>Gọi ngay sau khi materialize xong, trong cùng transaction, chứ không phải lúc khởi động: thứ
 * tự khởi động giữa catalog-service và inventory-service là tuỳ máy, nên một bộ dựng chạy lúc
 * khởi động sẽ thấy database rỗng đúng vào những lần Inventory lên trước.
 */
@Component
public class DemoOccupancy {

    private static final Logger log = LoggerFactory.getLogger(DemoOccupancy.class);

    /** Số khách ảo. Đủ để một suất có nhiều người mua khác nhau, không nhiều tới mức vô nghĩa. */
    private static final int BUYERS = 40;

    /** Tỉ lệ đã bán thấp nhất và cao nhất của một khu. Không khu nào bán hết sạch — còn chỗ để thử mua. */
    private static final double MIN_SOLD = 0.08;

    private static final double MAX_SOLD = 0.72;

    /** Ghế khoá vì lý do kỹ thuật (cột chắn, chỗ đặt bàn kỹ thuật). Hiếm, nhưng có thật. */
    private static final double BLOCKED_RATE = 0.01;

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final UUID demoOrganizationId;

    public DemoOccupancy(
            JdbcTemplate jdbc,
            @Value("${nexaticket.inventory.demo.occupancy:false}") boolean enabled,
            @Value("${nexaticket.inventory.demo.organization-id:00000000-0000-0000-0000-000000000000}")
                    UUID demoOrganizationId) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.demoOrganizationId = demoOrganizationId;
    }

    /** Không phải suất của tổ chức mẫu thì không đụng gì. */
    public void applyTo(UUID eventSessionId, UUID organizationId) {
        if (!enabled || !demoOrganizationId.equals(organizationId)) {
            return;
        }

        // Hạt giống suy từ id của suất: cùng một suất luôn ra cùng một sơ đồ, nên chụp màn hình
        // hai lần không ra hai bức ảnh khác nhau.
        Random random = new Random(eventSessionId.getMostSignificantBits() ^ eventSessionId.getLeastSignificantBits());

        List<Object[]> sold = new ArrayList<>();
        List<Object[]> blocked = new ArrayList<>();

        for (Map.Entry<String, List<UUID>> zone :
                seatsByZone(eventSessionId, "SEATED").entrySet()) {
            fillSeatedZone(zone.getValue(), random, sold, blocked);
        }
        for (Map.Entry<String, List<UUID>> zone :
                seatsByZone(eventSessionId, "STANDING").entrySet()) {
            fillStandingZone(zone.getValue(), random, sold);
        }

        jdbc.batchUpdate("UPDATE session_seats SET status = 'SOLD', holder_user_id = ? WHERE id = ?", sold);
        jdbc.batchUpdate("UPDATE session_seats SET status = 'BLOCKED', holder_user_id = NULL WHERE id = ?", blocked);

        log.info("Suất mẫu {}: đánh dấu {} chỗ đã bán và {} chỗ bị khoá", eventSessionId, sold.size(), blocked.size());
    }

    /**
     * Chỗ còn trống của một suất, gom theo khu và xếp theo đúng thứ tự nhìn thấy trên sơ đồ.
     *
     * <p>Thứ tự {@code pos_y, pos_x} là bắt buộc chứ không phải cho gọn: thuật toán bên dưới cắt
     * cụm liền kề bằng cách lấy các phần tử đứng cạnh nhau trong danh sách này. Xếp theo {@code id}
     * (ngẫu nhiên) thì "cụm liền kề" thành ra rải rác khắp khán phòng.
     */
    private Map<String, List<UUID>> seatsByZone(UUID eventSessionId, String admissionType) {
        Map<String, List<UUID>> byZone = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT zone_code, id
                  FROM session_seats
                 WHERE event_session_id = ?
                   AND admission_type = ?
                   AND status = 'AVAILABLE'
                 ORDER BY zone_code, pos_y, pos_x, seat_code
                """,
                rs -> {
                    byZone.computeIfAbsent(rs.getString("zone_code"), key -> new ArrayList<>())
                            .add(rs.getObject("id", UUID.class));
                },
                eventSessionId,
                admissionType);
        return byZone;
    }

    /**
     * Bán một khu ngồi thành từng cụm ghế liền nhau.
     *
     * <p>Mỗi cụm là một "đơn hàng": một tới bốn ghế cạnh nhau, cùng một người mua. Sau mỗi cụm
     * chừa lại một hai ghế — đó là nguồn gốc của những ghế lẻ mà khán phòng thật nào cũng có.
     *
     * <p><b>Bán từ trước ra sau.</b> Hàng đầu được chọn thường xuyên hơn hàng cuối, vì đó là thứ
     * tự người ta thật sự mua. Nhưng lượt quét luôn đi hết khu chứ không dừng lại khi đủ chỉ tiêu:
     * dừng giữa chừng sẽ để nguyên một mảng hàng cuối trắng tinh, và một khán phòng như vậy chỉ
     * tồn tại trong phút đầu tiên của đợt mở bán.
     *
     * <p>Quét nhiều lượt vì mỗi lượt chỉ lấp một phần: lượt đầu để lại nhiều khoảng trống, lượt sau
     * lấp dần. Giới hạn số lượt để không quay vô hạn khi chỉ tiêu cao hơn số ghế còn lại.
     */
    private void fillSeatedZone(List<UUID> seats, Random random, List<Object[]> sold, List<Object[]> blocked) {
        boolean[] taken = new boolean[seats.size()];
        int target = (int) Math.round(seats.size() * ratio(random));

        for (int i = 0; i < seats.size(); i++) {
            if (random.nextDouble() < BLOCKED_RATE) {
                taken[i] = true;
                blocked.add(new Object[] {seats.get(i)});
            }
        }

        int done = 0;
        for (int pass = 0; pass < 8 && done < target; pass++) {
            int i = random.nextInt(Math.max(1, Math.min(5, seats.size())));
            while (i < seats.size() && done < target) {
                if (taken[i]) {
                    i++;
                    continue;
                }
                // Mỗi lượt chỉ lấp một phần, và phần đầu khu đậm hơn phần cuối. Hai con số này
                // quyết định "hình dáng" của khán phòng: lấp quá nhanh thì lượt đầu đã đủ chỉ tiêu
                // và nửa sau của khu không bao giờ được chạm tới.
                double frontBias = 1.0 - 0.55 * (i / (double) seats.size());
                if (random.nextDouble() > 0.35 * frontBias) {
                    i += 1 + random.nextInt(3);
                    continue;
                }
                UUID buyer = buyer(random.nextInt(BUYERS));
                int block = 1 + random.nextInt(4);
                while (block > 0 && i < seats.size() && !taken[i] && done < target) {
                    taken[i] = true;
                    sold.add(new Object[] {buyer, seats.get(i)});
                    done++;
                    block--;
                    i++;
                }
                i += 1 + random.nextInt(3); // khoảng trống giữa hai đơn hàng
            }
        }
    }

    /**
     * Bán một khu đứng.
     *
     * <p>Không có cụm liền kề nào để dựng: đơn vị vé đứng là đơn vị ảo, không có vị trí, và không
     * bao giờ hiện trên sơ đồ (ADR-1012). Chỉ cần đúng số lượng để nhãn "còn bao nhiêu vé" nói
     * một con số thật.
     */
    private void fillStandingZone(List<UUID> units, Random random, List<Object[]> sold) {
        int target = (int) Math.round(units.size() * ratio(random));
        int i = 0;
        while (i < target) {
            UUID buyer = buyer(random.nextInt(BUYERS));
            int block = Math.min(1 + random.nextInt(4), target - i);
            for (int k = 0; k < block; k++, i++) {
                sold.add(new Object[] {buyer, units.get(i)});
            }
        }
    }

    private static double ratio(Random random) {
        return MIN_SOLD + random.nextDouble() * (MAX_SOLD - MIN_SOLD);
    }

    /**
     * Người mua ảo, id suy ra từ số thứ tự.
     *
     * <p>Cố ý KHÔNG dùng {@code UUID.randomUUID()}: id ổn định thì chạy lại bộ dựng trên database
     * trống cho ra cùng một nhóm người mua, và một id lạ xuất hiện trong {@code holder_user_id} là
     * dấu hiệu dữ liệu thật chứ không phải dữ liệu mẫu.
     */
    private static UUID buyer(int index) {
        return UUID.nameUUIDFromBytes(("nexaticket:demo:buyer:" + index).getBytes(StandardCharsets.UTF_8));
    }
}
