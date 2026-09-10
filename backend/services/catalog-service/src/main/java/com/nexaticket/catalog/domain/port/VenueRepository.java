// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

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
}
