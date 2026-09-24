// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

import com.nexaticket.catalog.domain.model.StageArea;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu trữ địa điểm. */
public interface VenueRepository {

    void save(Venue venue);

    void addZone(VenueZone zone);

    /**
     * Đặt lại sân khấu của một địa điểm đã có.
     *
     * <p>Tách khỏi {@link #save} vì {@code save} là INSERT: sân khấu đổi nhiều lần trong lúc ban
     * tổ chức xếp sơ đồ, còn địa điểm thì tạo đúng một lần.
     *
     * @param stage {@code null} đưa địa điểm về sân khấu mặc định
     */
    void updateStage(UUID venueId, StageArea stage);

    /**
     * Ghi địa điểm cùng toàn bộ khu của nó trong <b>một</b> lần đi database cho phần khu.
     *
     * <p>Một khung concert có hàng chục khu. Gọi {@link #addZone} trong vòng lặp là hàng chục lần
     * đi-về mạng cho một thao tác mà người dùng đang đứng chờ; {@code batchUpdate} gộp chúng lại.
     * Với việc sinh sơ đồ từ khung thì đây là toàn bộ phần nặng của thao tác — ghế thật không sinh
     * ở đây mà ở inventory-service lúc publish.
     */
    void saveWithZones(Venue venue, List<VenueZone> zones);

    /**
     * Thay toàn bộ sơ đồ khu của một địa điểm.
     *
     * <p>Khớp theo {@code zoneCode} chứ không xoá sạch rồi chèn lại: mã khu là thứ hạng vé trỏ
     * vào. Xoá sạch nghĩa là "VIP: 100 ghế → 150 ghế" cũng làm bay luôn mức giá đã khai cho khu
     * VIP, và ban tổ chức phải nhập lại một thứ họ không hề đụng tới.
     *
     * @return những gì đã đổi, để màn hình nói được đúng thứ vừa xảy ra
     */
    ZoneReplacement replaceZones(UUID venueId, List<VenueZone> zones);

    /**
     * @param removedTicketTypes số hạng vé bị xoá theo khu không còn nữa. Luôn thuộc sự kiện còn
     *     nháp — lệnh này từ chối chạy khi địa điểm có sự kiện đã từng lên bán.
     */
    record ZoneReplacement(int inserted, int updated, int removedZones, int removedTicketTypes) {}

    /**
     * Đọc địa điểm kèm khu.
     *
     * <p>Nhận cả {@code organizationId} chứ không chỉ {@code id}: lọc theo tổ chức ngay trong câu
     * truy vấn biến IDOR thành "không tìm thấy" thay vì thành một bước kiểm tra mà ai đó sẽ quên.
     */
    Optional<Venue> findById(UUID organizationId, UUID venueId);

    List<Venue> findAllByOrganization(UUID organizationId);

    /**
     * Địa điểm của một sự kiện <b>đã publish</b>, tra theo slug, không lọc theo tổ chức.
     *
     * <p>Tra theo sự kiện chứ không theo suất diễn: mặt bằng thuộc địa điểm, và mọi suất của một
     * sự kiện diễn ra ở cùng một địa điểm. Nhận {@code eventSessionId} ở đây sẽ dựng một đường đọc
     * nói rằng sơ đồ đổi theo suất — nó không đổi, và ai đó sẽ dựa vào điều ngược lại.
     *
     * <p>Đây là đường đọc duy nhất trong catalog không mang {@code organizationId}, và nó an toàn
     * vì điều kiện thay thế nằm trong chính câu truy vấn: chỉ sự kiện {@code PUBLISHED} mới giải
     * ra được. Sơ đồ của một sự kiện đang bán là dữ liệu công khai — cùng mức với
     * {@code GET /v1/events/{slug}}.
     */
    Optional<Venue> findByPublishedEventSlug(String slug);
}
