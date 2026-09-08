# Lịch sử sửa đổi — Kiến trúc v2

## Bản 5 — Kế hoạch triển khai + hướng giao diện

| Tài liệu | Nội dung |
| --- | --- |
| [plan/README.md](plan/README.md) | Monorepo 11 service, 6 starter dùng chung và ranh giới cứng, RabbitMQ topology as code, cách chạy local với 4 deployable, branching, CI theo path filter |
| [plan/backend.md](plan/backend.md) | Khuôn service, cross-cutting, migration theo service, giai đoạn G0–G7, thuật toán hold có trần, test |
| [plan/frontend.md](plan/frontend.md) | 4 app, `packages/seatmap` dùng chung, trình thiết kế chỗ ngồi, `web-platform` mới |
| [ui-direction.md](ui-direction.md) | Hướng giao diện v2 — thay `docs/ui/design-direction.md` |

### Giao diện đổi hệ

Tham chiếu bố cục ticketbox.vn: lấy **cấu trúc thông tin** (header ưu tiên tìm kiếm, hero xoay vòng, chip thể loại, dải sự kiện cuộn ngang, thẻ 4 dòng, CTA dính đáy), **không** lấy logo, ảnh, câu chữ hay mã màu thương hiệu của họ.

| | v1 | v2 |
| --- | --- | --- |
| Nền customer/admin/platform | Tối `#0c1210` | **Sáng `#ffffff`** |
| Màu chính | Lime `#e8f56d` | **Đỏ ấm `#c02a2a`** + vàng kim `#f2b705` |
| Trang chủ | Brand hero, không card ở màn đầu | **Nội dung trước** |
| Scanner | Tối | **Vẫn tối** — dùng ngoài trời buổi tối, có lý do |

Acceptance criteria cũ của `C-HOME` (*"không card grid ở first viewport"*) không còn áp dụng. Bảng màu sơ đồ ghế phải làm lại vì trước đó thiết kế cho nền tối.

### Điểm đáng chú ý trong plan

1. **Ranh giới thư viện dùng chung** là chỗ dễ giết microservices nhất — 6 starter kỹ thuật được phép, entity/DTO/enum nghiệp vụ bị cấm. Kèm luật ArchUnit cấm import chéo context.
2. **Chạy local 11 JVM là bất khả thi** (6–8 GB RAM). Đây là lập luận thực dụng mạnh nhất cho lộ trình B.
3. **Ba spike bắt buộc ở G0** (hold, sổ cái, webhook) — không dồn rủi ro về cuối như v1.
4. **Nhả chỗ phải là một hàm duy nhất** dùng cho cả bốn đường, nếu không sẽ quên xoá `holder_user_id` ở một đường.
5. **`packages/seatmap` dùng chung** cho màn khách và màn xem trước của trình thiết kế — viết hai lần là cầm chắc lệch nhau.
6. **Xem trước sơ đồ gọi `GET /preview` của backend**, frontend không tự tính chỗ — nếu tính ở client sẽ lệch với kết quả materialize.

---

## Bản 4.2 — Trần mua vé cho tổ chức cấu hình

[ADR-1014](adr/ADR-1014-configurable-purchase-limits.md). Trần đổi từ hằng số cứng thành cấu hình ba tầng: **nền tảng (cứng) → tổ chức → suất diễn**, giá trị hiệu lực là `coalesce(suất, tổ chức, nền tảng)`. Bổ sung luôn trần còn treo: **cộng dồn mỗi tài khoản trên mỗi suất diễn**.

| Điểm thiết kế | Nội dung |
| --- | --- |
| Kiểm trần cứng lúc **ghi cấu hình** | Đường giữ chỗ chỉ đọc một cột, không tính `min()` ở 10k đồng thời |
| Sao trần vào `session_inventory` lúc materialize | Inventory không gọi Catalog khi giữ chỗ — event-carried state |
| `session_seats.holder_user_id` | Biến phép đếm xuyên ba service thành một câu đếm có index trong một bảng |
| `pg_advisory_xact_lock(user, session)` | Chống mở hai tab; không tranh chấp giữa các người dùng khác nhau |
| `purchaseAllowance` trong `GET /seats` | Khách biết trước còn mua được mấy vé, không bị từ chối ở bước cuối |

Mã lỗi mới: `PURCHASE_LIMIT_EXCEEDED`, `LIMIT_EXCEEDS_PLATFORM_CEILING`.

Cập nhật: `README.md` §4, `services.md` (catalog + inventory), `venue-seating-model.md` §10, `tactical-ddd.md`.

**Rủi ro phải test kỹ:** `holder_user_id` phải được xoá ở **cả bốn** đường nhả chỗ — hết hạn giữ chỗ, đơn hết hạn, huỷ đơn, hoàn tiền. Quên một đường là khách bị khoá hạn mức oan.

---

## Bản 4.1 — Chốt hai tham số

| Tham số | Chốt | Lý do |
| --- | --- | --- |
| `VENUE_SETUP_MINUTES` / `VENUE_TEARDOWN_MINUTES` | **240 / 180 phút** mặc định, superadmin chỉnh theo từng địa điểm | Quán cà phê cần 1–2 giờ, sân vận động cần 1–2 ngày — một con số cứng không dùng được |
| Trần giữ chỗ | **Ngồi ≤ 8, đứng ≤ 10, tổng ≤ 10** | Nhóm đi vé đứng đông hơn; tách đơn kéo theo hai lần chuyển khoản riêng, ma sát đủ để mất khách |

Ghi vào: [README §4 Hằng số nghiệp vụ](README.md#4-hằng-số-nghiệp-vụ) (mục mới), `venue-seating-model.md` §10 + §11, `tactical-ddd.md`, [ADR-1012](adr/ADR-1012-standing-admission-inventory.md). Thêm mã lỗi `TOO_MANY_SEATS`.

**Còn treo:** trần vé mỗi **tài khoản** trên mỗi suất diễn. Trần-mỗi-lần-giữ không cản được phe vé (đặt 13 đơn liên tiếp là xong); công cụ đúng là giới hạn tổng số vé một người mua được cho một suất. Hiện chưa có trong thiết kế.

---

## Bản 4 — Địa điểm riêng, ba kiểu concert, cảnh báo trùng lịch

Ba câu trả lời cho các việc còn treo ở bản 3:

| Câu hỏi treo | Chốt |
| --- | --- |
| Tổ chức có được tự tạo địa điểm riêng không? | **Có** — `scope = ORGANIZATION`, và **vẫn có zone `FIXED`** |
| Có cần vé đứng không? | **Có, cả ba kiểu** — toàn ghế ngồi, vừa ngồi vừa đứng, chỉ đứng |
| Trùng lịch địa điểm: cảnh báo hay chặn? | **Cảnh báo**, vì hệ thống không nắm dữ liệu thuê địa điểm |

### ADR mới

| ADR | Nội dung |
| --- | --- |
| [ADR-1012](adr/ADR-1012-standing-admission-inventory.md) | Vé đứng: đơn vị tồn kho ảo, cấp phát bằng `FOR UPDATE SKIP LOCKED`, **không** dùng Redis |
| [ADR-1013](adr/ADR-1013-venue-schedule-conflict-warning.md) | Trùng lịch: cảnh báo không chặn; quy tắc lộ thông tin; truy vấn xuyên tenant có kiểm soát |

### Thay đổi theo tài liệu

| Tài liệu | Thay đổi |
| --- | --- |
| `venue-seating-model.md` | §2 tách "sửa mặt bằng" khỏi "thiết kế chỗ ngồi"; §3 thêm `setup_minutes`/`teardown_minutes`; §4 thêm `admission_type` + index cấp phát; §7 viết lại (địa điểm riêng **có** zone `FIXED`); §8 mới — ma trận `kind` × `admission_type` + ba kiểu concert; §9 mới — tồn kho vé đứng; §10 API mở rộng; §11 mới — cảnh báo trùng lịch; §12 UI thêm 2 màn platform |
| `services.md` | catalog: API địa điểm riêng cho tổ chức, `promote`, `venue-conflicts`; inventory: hold nhận `standing[]`, thêm đường `SKIP LOCKED` |
| `context-map.md` | Thêm `AdmissionType`, đơn vị vé đứng, `VenueScheduleConflict`; ánh xạ 2 yêu cầu mới |
| `tactical-ddd.md` | `SeatHold` nhận cả chỗ ngồi lẫn chỗ đứng; `EventSeatingPlan` thêm bất biến về `admission_type` |
| `README.md` | Danh sách yêu cầu lên 8 mục; bảng quyền tách địa điểm dùng chung vs riêng; ADR-0008 ghi nhận ngoại lệ hẹp; việc chưa chốt còn 2 mục |

### Điểm thiết kế đáng chú ý

1. **Ranh giới "áp cứng" nằm ở thao tác, không ở quyền sở hữu.** Sửa mặt bằng (tạo `VenueLayoutVersion` mới) là quyền của chủ địa điểm; thiết kế chỗ ngồi cho sự kiện (`EventSeatingPlan`) không bao giờ sửa được zone `FIXED`. Nhờ vậy địa điểm riêng của tổ chức vẫn có ghế áp cứng mà bất biến vẫn giữ.
2. **Vé đứng không sinh cơ chế chống oversell thứ hai.** Đơn vị ảo + `SKIP LOCKED` dùng lại nguyên chốt chặn `seat_hold_items` unique index. Chỉ khác cách *chọn*, không khác cách *bảo vệ*.
3. **Ba kiểu concert là tổ hợp của một ma trận 2×2**, không cần khái niệm mới — tổ chức đổi kiểu chỉ bằng `EventSeatingPlan`, cùng một địa điểm.
4. **Cảnh báo trùng lịch phải chặn rò rỉ.** Suất diễn chưa công bố của tổ chức khác chỉ được báo bằng câu chung, không lộ tên sự kiện hay tên tổ chức — nếu không, tính năng này thành công cụ dò lịch đối thủ.
5. Phạm vi ảnh hưởng vẫn **chỉ Catalog + một đường cấp phát trong Inventory**. Ordering, Payment, Ledger, Payout, Ticketing không phân biệt vé ngồi với vé đứng.

---

## Bản 3 — Địa điểm dùng chung: khu vực cố định và khu vực linh hoạt

Yêu cầu: một địa điểm dùng chung cho nhiều tổ chức; mỗi tổ chức tự thiết kế khu vực ghế cho từng sự kiện; ngoài ra có khu vực ghế áp cứng không thay đổi được.

| Tài liệu | Thay đổi |
| --- | --- |
| [venue-seating-model.md](venue-seating-model.md) | **Mới.** Mô hình đầy đủ: `Venue` → `VenueLayoutVersion` → `VenueZone` (`FIXED` \| `FLEXIBLE`); `EventSeatingPlan` của tổ chức; `seat_code`; materialize; sửa sơ đồ sau khi bán vé; API và mã lỗi |
| [ADR-1011](adr/ADR-1011-shared-venue-fixed-and-flexible-zones.md) | **Mới.** Thay mô hình `venue_seat_maps`/`seat_map_seats` của v1 |
| `services.md` | catalog-service: API địa điểm tách làm hai nhóm (platform vs tổ chức); thêm nhóm `seating-plans`; preflight publish lên 4 mục; inventory giữ thêm `seat_code`/`zone_code` |
| `context-map.md` | Thêm mục thuật ngữ Catalog; ánh xạ 2 yêu cầu mới; sửa nghĩa của từ `Seat` |
| `tactical-ddd.md` | `Venue` đổi entity bên trong; thêm aggregate `EventSeatingPlan` |
| `README.md` | Danh sách yêu cầu; bảng quyền tách "tạo địa điểm + ghế áp cứng" khỏi "thiết kế khu vực linh hoạt"; G1 giãn từ 5–8 lên 5–9 tuần; thêm 3 việc chưa chốt |

### Điểm thiết kế đáng chú ý

1. **Ranh giới quyền:** kết cấu vật lý thuộc chủ địa điểm, quyết định thương mại thuộc ban tổ chức. Tổ chức không sửa được định nghĩa ghế cố định, nhưng toàn quyền quyết định có bán ghế đó không và bán giá nào.
2. **Ghim `layoutVersionId`:** cải tạo địa điểm không làm hỏng sự kiện đã bán vé — cùng nguyên tắc snapshot mà v1 dùng cho giá và tài khoản ngân hàng.
3. **`seat_code` thay `seat_map_seat_id`:** ghế nay đến từ hai nguồn, cần một khoá thống nhất. Bất biến chống oversell giữ nguyên hình dạng `UNIQUE (event_session_id, seat_code)`.
4. **Trạng thái `FROZEN`:** sau khi có ghế bán, chỉ còn thao tác cộng thêm và thao tác trên ghế chưa bán. Thêm khối ghế mới vẫn được (nhu cầu thật); bỏ ghế đã bán thì không.
5. **Phạm vi ảnh hưởng chỉ trong Catalog.** Inventory, Ordering, Payment, Ledger, Payout, Ticketing không đổi một dòng. Đây là bằng chứng ranh giới bounded context đặt đúng chỗ.

### Đề xuất thêm, chưa chốt

- **Địa điểm riêng của tổ chức** (`venues.scope = ORGANIZATION`, toàn bộ zone `FLEXIBLE`) để tránh nút thắt phải chờ superadmin cho mọi địa điểm nhỏ.
- **Khu vực vé đứng** (`admission_type = STANDING`), khuyến nghị cài bằng ghế ảo để không phải viết đường thứ hai cho cơ chế chống oversell.
- **Trùng lịch cùng địa điểm** giữa hai tổ chức: cảnh báo mềm, không chặn cứng.

---

## Bản 2 — Superadmin sở hữu tenancy và tài chính; chỉ RabbitMQ

Bốn yêu cầu mới từ chủ dự án:

1. Superadmin tạo ra các tổ chức.
2. Chỉ dùng RabbitMQ, không dùng Kafka.
3. Thanh toán tiền do superadmin quản lý.
4. Tổ chức chỉ biết số tiền và số vé đã bán.

### ADR

| ADR | Thay đổi |
| --- | --- |
| [ADR-1009](adr/ADR-1009-rabbitmq-only.md) | **Mới.** Chỉ RabbitMQ; `outbox` giữ vĩnh viễn và đóng vai trò nhật ký sự kiện. Thay thế ADR-1003 |
| [ADR-1010](adr/ADR-1010-superadmin-tenancy-and-finance-visibility.md) | **Mới.** Superadmin tạo tổ chức; tổ chức không chạm vào tiền. Thay thế ADR-1007 |
| [ADR-1003](adr/ADR-1003-kafka-event-backbone.md) | **Superseded.** Giữ lại để ghi lý do đã cân nhắc |
| [ADR-1007](adr/ADR-1007-self-service-organizations.md) | **Superseded.** Giữ lại để ghi lý do đã cân nhắc |

### Thay đổi theo tài liệu

| Tài liệu | Thay đổi |
| --- | --- |
| `README.md` | Danh sách yêu cầu; sơ đồ hệ thống (RabbitMQ, tách deployable `finance`, thêm `web-platform`); bảng "ai làm được gì"; ước lượng giảm từ 26–34 xuống 24–30 tuần; lộ trình G0–G7; danh sách việc chưa chốt |
| `context-map.md` | §4 thuật ngữ: bỏ `OrganizationVerification`, thêm `SUPER_ADMIN`, `SettlementReport`, `GrossSales`; §5 ánh xạ lại 5 yêu cầu |
| `services.md` | Thêm mục "Mô hình vai trò"; identity chỉ superadmin tạo org; catalog thêm thư viện địa điểm dùng chung và **bỏ mã lỗi `NO_ACTIVE_BANK_ACCOUNT`**; realtime-gateway dùng fanout exchange; payment/ledger/payout chuyển sang chỉ `SUPER_ADMIN`; thêm mục `reporting` với `sales-summary` |
| `tactical-ddd.md` | Messaging đổi sang RabbitMQ; exchange/routing key thay cho topic; JSON Schema trong repo thay cho schema registry; thêm luật consumer không phụ thuộc thứ tự |
| `custodial-funds.md` | §7 bỏ cổng `KYC_REQUIRED`, chi trả do superadmin khởi tạo; §8 viết lại thành "thẩm định khi onboarding" + bảng phạm vi tổ chức nhìn thấy; §10 cập nhật phòng thủ gian lận; §11 cập nhật checklist |
| `sagas.md` | Kafka → RabbitMQ trong mọi sơ đồ; thêm mục "không dựa vào thứ tự"; saga hoàn tiền và chi trả do superadmin khởi tạo; thêm bước gửi `settlement-report`; thêm test thứ tự đảo và test replay |

### Hệ quả dây chuyền đáng chú ý

1. **Mã lỗi `NO_ACTIVE_BANK_ACCOUNT` bị gỡ khỏi preflight publish.** Điều kiện này tồn tại vì tiền chảy vào tài khoản tổ chức. Giờ tiền chảy vào tài khoản ký quỹ nền tảng — luôn tồn tại. Preflight còn 3 mục. Màn `A-BANK` của v1 chuyển sang khu vực platform.
2. **Máy trạng thái KYC bị gỡ khỏi phần mềm.** `organizations.status` quay về `ACTIVE` | `SUSPENDED` như v1. Việc thẩm định là thao tác của con người trước khi tạo tổ chức.
3. **Bảng kê thanh toán (`settlement-report`) trở thành bắt buộc.** Vì tổ chức không tự kiểm chứng được số tiền thực nhận, đây là kênh đối chiếu duy nhất của họ.
4. **Test thứ tự đảo trở thành bắt buộc.** RabbitMQ không bảo đảm thứ tự — đây là bài test đặc thù mà Kafka sẽ không đòi hỏi.
5. **Cần app thứ tư: `web-platform`** cho superadmin (tạo tổ chức, sổ cái, đối soát, chi trả). v1 gộp phần platform vào `web-admin`; với ranh giới tài chính chặt như hiện tại, tách app riêng là hợp lý hơn — chưa chốt.

---

## Bản 1 — Microservices + DDD + Custodial funds

Ba yêu cầu ban đầu: microservices, kiến trúc DDD, nền tảng giữ tiền. Tạo ADR-1001…1008 và 6 tài liệu thiết kế. Xem lịch sử git.
