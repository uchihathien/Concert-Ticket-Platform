# Catalog & Admin API (v1)

Base: `/v1`. Org-scoped routes lấy tenant từ **đường dẫn**, và `TenantFilter` kiểm membership từ đó.

## Vì sao không phải `/v1/admin/**`

Bản đầu của tài liệu này viết `/v1/admin/events`. Khi dựng thật thì đường đó không dùng được:
`TenantFilter` lấy tổ chức từ đoạn `/organizations/{id}` trên đường dẫn và trả **404** nếu người
gọi không phải thành viên. Với `/v1/admin/events` thì không có tổ chức nào trên đường dẫn, và
filter rơi về "nếu người này chỉ thuộc đúng một tổ chức thì lấy tổ chức đó" — im lặng đúng với
phần lớn người dùng, và im lặng **sai** với người thuộc hai tổ chức: họ tạo sự kiện cho tổ chức mà
họ không định chọn, và không có gì báo cho họ biết.

Nên mọi route quản trị đều mang `organizationId`.

## Vì sao không có seat map

Bản đầu có `POST /admin/venues/{id}/seat-maps`, `seats:bulk`, `activate`, và một bước wizard "gán
ghế → hạng vé". Cách dựng hiện tại thay tất cả bằng **khu hình chữ nhật**: mỗi khu khai số hàng ×
số ghế mỗi hàng, hệ thống tự sinh mã chỗ lúc publish.

Đánh đổi: mất khả năng tả khu có hình dạng bất thường — bù lại bằng cách tách thành nhiều khu chữ
nhật. Được lại: màn hình nhập liệu làm xong trong một phút thay vì một buổi, không cần vòng đời
DRAFT/ACTIVE/ARCHIVED cho sơ đồ, và bất biến "mọi ghế bán được đều có giá" do **cấu trúc** bảo đảm
(hạng vé gắn vào khu) chứ không do một bước kiểm tra mà ai đó sẽ quên.

## Admin — Địa điểm và khu

| Method | Path | Role | Body / ghi chú |
| --- | --- | --- | --- |
| GET | `/v1/organizations/{orgId}/venues` | EVENT_MANAGER+ | kèm khu |
| POST | `/v1/organizations/{orgId}/venues` | EVENT_MANAGER+ | `{ name, city, address? }` |
| POST | `/v1/organizations/{orgId}/venues/{venueId}/zones` | EVENT_MANAGER+ | xem dưới |

Body của khu:

```json
{ "zoneCode": "A", "name": "Tầng 1", "kind": "SEATED", "rowCount": 12, "seatsPerRow": 22 }
{ "zoneCode": "GA", "name": "Sân trung tâm", "kind": "STANDING", "capacity": 3000 }
```

`kind` quyết định trường nào bắt buộc. Luật này được ép ở ba nơi độc lập: constructor của
`VenueZone`, ràng buộc `ck_zone_shape` của database, và `@Pattern` ở request.

## Admin — Sự kiện

| Method | Path | Ghi chú |
| --- | --- | --- |
| GET | `/v1/organizations/{orgId}/events` | gồm cả nháp |
| GET | `/v1/organizations/{orgId}/events/{eventId}` | kèm `blockers` |
| POST | `/v1/organizations/{orgId}/events` | tạo DRAFT; `slug` bỏ trống thì sinh từ `title` |
| PATCH | `/v1/organizations/{orgId}/events/{eventId}` | sửa mô tả; **không** đổi được slug và địa điểm |
| DELETE | `/v1/organizations/{orgId}/events/{eventId}` | chỉ DRAFT; 204 |
| POST | `/v1/organizations/{orgId}/events/{eventId}/publish` | materialize |
| POST | `/v1/organizations/{orgId}/events/{eventId}/unpublish` | ẩn khỏi catalog |
| POST | `/v1/organizations/{orgId}/events/{eventId}/cancel` | trạng thái cuối |

Slug và địa điểm không sửa được vì slug đã nằm trên link người ta chia sẻ, còn tồn kho đã dựng theo
khu của địa điểm cũ. Đổi hai thứ đó là tạo sự kiện mới.

## Admin — Suất diễn và hạng vé

| Method | Path |
| --- | --- |
| POST | `/v1/organizations/{orgId}/events/{eventId}/sessions` |
| PATCH | `…/events/{eventId}/sessions/{sessionId}` |
| DELETE | `…/events/{eventId}/sessions/{sessionId}` |
| POST | `…/events/{eventId}/sessions/{sessionId}/ticket-types` |
| PATCH | `…/sessions/{sessionId}/ticket-types/{ticketTypeId}` |
| DELETE | `…/sessions/{sessionId}/ticket-types/{ticketTypeId}` |

Mọi lệnh sửa đều trả về **chi tiết sự kiện sau khi đổi**, gồm cả `blockers` đã tính lại — UI không
phải gọi thêm một request để cập nhật checklist.

Sửa và xoá chỉ được khi sự kiện **chưa lên bán**; đang bán thì trả `INVALID_EVENT_STATE`. Tồn kho
bên inventory-service được dựng một lần lúc publish và mang theo giá đã chốt, nên sửa giá ở đây khi
đang bán sẽ tạo hai mức giá cho cùng một chỗ: giá khách nhìn thấy và giá khách bị tính. Cách làm
đúng là rút xuống → sửa → publish lại; publish lại an toàn vì Inventory dùng `ON CONFLICT DO
NOTHING`, ghế đã bán không bị dựng lại thành trống.

Một hạng vé cho một khu, ở một suất — khoá `uq_type_zone`. Khai lần thứ hai cho cùng khu trả
`ZONE_ALREADY_PRICED`.

## Lỗi khi publish

| Code | HTTP | Nghĩa |
| --- | --- | --- |
| `PUBLISH_BLOCKED` | 409 | còn vướng mắc; danh sách ở `meta.blockers` |
| `INVALID_EVENT_STATE` | 409 | trạng thái không cho phép thao tác này |
| `ZONE_ALREADY_PRICED` | 409 | khu đã có hạng vé ở suất đó |

`meta.blockers` là một trong: `VENUE_WITHOUT_ZONE`, `NO_SESSION`, `SESSION_WITHOUT_TICKET_TYPE`,
`INVALID_SALES_WINDOW`. Cùng danh sách đó nằm sẵn ở `GET /events/{eventId}` để màn hình publish vẽ
checklist — cả hai đều đọc từ `Event.preflight`, nên checklist và nút Publish không thể lệch nhau.

**`NO_ACTIVE_BANK_ACCOUNT` đã bỏ.** Tài khoản ngân hàng thuộc payment-service; gọi đồng bộ sang đó
lúc publish sẽ khiến Catalog không publish nổi mỗi khi Payments trục trặc. Chỗ đúng cho ràng buộc
đó là lúc tạo đơn hàng, nơi tiền thật sự chảy.

## Trạng thái sự kiện

`DRAFT` → `PUBLISHED` → `UNPUBLISHED` → `PUBLISHED` …, và `CANCELLED` là trạng thái cuối từ bất kỳ
đâu. **Không có `ARCHIVED`** — nếu SDK hay UI khai giá trị đó thì đó là lỗi, backend không bao giờ
trả về.

Rút xuống ≠ huỷ. Rút xuống là "tạm dừng bán, sẽ bán lại"; huỷ là "sự kiện không diễn ra". Cả hai
đều **không** xoá tồn kho hay vé đã bán: khách đã trả tiền vẫn phải vào được, và dữ liệu phải còn
để hoàn tiền cùng đối soát. Hoàn tiền là quy trình ngoài hệ thống ở MVP.

## Catalog công khai

| Method | Path | Auth |
| --- | --- | --- |
| GET | `/v1/events?query&city&category&page&size` | công khai |
| GET | `/v1/events/{slug}` | công khai |

Chỉ trả sự kiện `PUBLISHED`, và điều kiện đó nằm trong mệnh đề `WHERE` của SQL chứ không ở tầng
trên — để một lần refactor quên mất không làm lộ toàn bộ sự kiện chưa công bố.

`GET /v1/events` trả kèm `cities` để trang danh sách dựng bộ lọc mà không phải gọi thêm request.
Cả hai endpoint đặt `Cache-Control: max-age=120, public`: catalog đổi theo ngày chứ không theo
giây, và đây là trang chịu tải cao nhất lúc mở bán.

### `GET /v1/events/{slug}`

```json
{
  "slug": "dem-nhac-mua-thu",
  "title": "Đêm nhạc Mùa Thu",
  "city": "Hà Nội",
  "venueName": "Nhà hát Lớn Hà Nội",
  "sessions": [
    {
      "id": "uuid",
      "startsAt": "2026-11-01T12:00:00Z",
      "fromPriceVnd": 500000,
      "tiers": [{ "id": "uuid", "name": "Hạng A", "priceVnd": 1200000, "zoneCode": "A", "capacity": 264 }]
    }
  ]
}
```

**Tiền là `priceVnd`, số nguyên đồng VND.** Tên cũ `unit_price_cents` trong
[data-model.md](../01-foundation/data-model.md) không còn dùng ở đâu cả — không có `_cents` trong
schema của catalog.

## Dữ liệu mẫu

catalog-service dựng sẵn một catalog mẫu lúc khởi động (`CATALOG_DEMO_DATA`, mặc định bật). Một
catalog rỗng làm cả hệ thống trông như hỏng, và người mới clone repo không phân biệt được "chưa có
dữ liệu" với "code hỏng". **Đặt `CATALOG_DEMO_DATA=false` ở production.**

Dữ liệu mẫu đứng tên một tổ chức không có thật, nên nó hiện ở trang khách nhưng không hiện trong
khu quản trị. Muốn có dữ liệu để bấm thử trong app tổ chức thì đặt `CATALOG_DEMO_ORG_ID` bằng id
tổ chức thật của bạn.
