# Thẻ hành động của chatbox AI

Mỗi câu trả lời của `POST /v1/chat/agent/support` có `answer` (chữ) và có thể kèm **một** thẻ `action`
(dữ liệu có cấu trúc để giao diện vẽ nút). `GET /v1/chat/agent/sessions/{id}/messages` trả lại thẻ đang
mở của phiên trong `action`, để tải lại trang vẫn thấy thẻ.

Thẻ **không bao giờ** dựng từ chữ của mô hình: mọi trường lấy từ catalog, inventory, ordering hoặc
bảng đề xuất. Đơn chỉ ra đời khi khách bấm nút xác nhận (ADR-2001).

| `kind` | Khi nào | Nguồn dữ liệu | Nút trên giao diện |
|---|---|---|---|
| `CHOICES` | Đặt vé còn thiếu sự kiện, suất, hạng vé hoặc số vé | catalog + `GET /internal/sessions/{id}/seat-status` | Mỗi chip gửi `value` như một tin nhắn |
| `BOOKING_PROPOSAL` | Đủ thông tin — đây là bước xác nhận | `booking_proposals` (chưa giữ chỗ) | Xác nhận đặt · Đổi lựa chọn · Huỷ |
| `PAYMENT` | Đã xác nhận, hoặc khách hỏi "mình thanh toán chưa" | đơn của ordering + đề xuất + catalog | Thanh toán ngay · Huỷ giữ chỗ · (hết hạn) Đặt lại |

Ngoài ba thẻ trên, giao diện tự hiện thẻ **mời đăng nhập lại** khi một lượt chat trả 401 (phiên hết hạn
giữa chừng). Backend không trả thẻ này: endpoint chat bắt buộc đăng nhập, nên một khách chưa đăng nhập
không gọi tới được để nhận nó.

## Luồng đặt vé bằng chip

Do `ChatBookingFlow` điều phối, **không qua mô hình** — mỗi bước là một tra cứu có đáp án duy nhất, nên
trả lời tức thì (dưới 0,5 giây) và đúng mọi lần. Câu không thuộc luồng vẫn tới mô hình như cũ.

```
"cho mình 2 vé đêm nhạc Trịnh"
   → tìm sự kiện (chỉ sự kiện còn suất sắp diễn)   nhiều kết quả → CHOICES step=EVENT
   → suất đang mở bán                              nhiều suất    → CHOICES step=SESSION
   → hạng vé còn chỗ (kèm giá, số chỗ còn)         nhiều hạng    → CHOICES step=ZONE
   → số vé (1–6 qua chat)                          chưa nói      → CHOICES step=QUANTITY
   → BookingProposalUseCase.propose(...)                          → BOOKING_PROPOSAL
khách bấm Xác nhận đặt → POST /v1/chat/agent/proposals/{id}/confirm → PAYMENT (có VietQR)
"mình thanh toán chưa"                                            → PAYMENT có status mới
"Đổi lựa chọn"   → huỷ đề xuất đang mở, hỏi lại hạng vé (giữ sự kiện, suất, số vé)
```

Bước nào chỉ có một lựa chọn thì luồng tự chọn và đi tiếp. Chip đang chờ được lưu ở bảng
`chat_booking_drafts` (V0111): chip gửi lại một **câu** ("Hạng VIP") để hội thoại đọc lại vẫn tự nhiên,
và server đối chiếu câu đó với đúng danh sách chip vừa hiện — không đoán ngược mã khu từ chữ. Bấm Xác
nhận hai lần vẫn chỉ một đơn: khoá chống trùng của giữ chỗ và đặt đơn suy từ id đề xuất, lần hai nhận
`409 PROPOSAL_ALREADY_CONFIRMED` kèm thẻ đơn cũ trong `meta`.

## Ví dụ JSON

### `CHOICES`

```json
{
  "sessionId": "5b1c…",
  "answer": "Đêm nhạc Trịnh · Ru Đời Đi Nhé · 19:00 ngày 25/10/2026. Bạn chọn hạng vé nào?",
  "toolsUsed": [],
  "action": {
    "kind": "CHOICES",
    "step": "ZONE",
    "prompt": "Bạn chọn hạng vé nào?",
    "options": [
      { "label": "Tầng trệt", "value": "Hạng Tầng trệt", "hint": "950.000đ · còn 339 chỗ" },
      { "label": "Tầng lửng", "value": "Hạng Tầng lửng", "hint": "680.000đ · còn 152 chỗ" },
      { "label": "Ban công", "value": "Hạng Ban công", "hint": "400.000đ · còn 83 chỗ" }
    ]
  }
}
```

`step` là một trong `EVENT`, `SESSION`, `ZONE`, `QUANTITY`. `hint` vắng khi không có gì thêm (inventory
không trả lời thì chip vẫn hiện, chỉ thiếu "còn N chỗ").

### `BOOKING_PROPOSAL`

```json
{
  "kind": "BOOKING_PROPOSAL",
  "id": "8f0e…",
  "summary": "2 vé Tầng trệt · Đêm nhạc Trịnh · Ru Đời Đi Nhé · 19:00 ngày 25/10/2026",
  "eventTitle": "Đêm nhạc Trịnh · Ru Đời Đi Nhé",
  "sessionStartsAt": "2026-10-25T12:00:00Z",
  "zoneCode": "A",
  "quantity": 2,
  "totalVnd": 1900000,
  "expiresAt": "2026-10-23T09:57:00Z"
}
```

### `PAYMENT`

```json
{
  "kind": "PAYMENT",
  "orderId": "c3a9…",
  "orderNumber": "NT-261023-C6AYZ8",
  "totalVnd": 1900000,
  "vietQrPayload": "000201010212…",
  "paymentReference": "NT261023C6AYZ8",
  "checkoutUrl": "https://pay.payos.vn/web/…",
  "paymentExpiresAt": "2026-10-23T10:02:00Z",
  "eventTitle": "Đêm nhạc Trịnh · Ru Đời Đi Nhé",
  "eventSlug": "dem-nhac-trinh",
  "sessionStartsAt": "2026-10-25T12:00:00Z",
  "venueName": "Nhà hát Hòa Bình",
  "lines": [{ "zoneName": "Tầng trệt", "quantity": 2, "unitPriceVnd": 950000 }],
  "status": "AWAITING_PAYMENT"
}
```

- `vietQrPayload` chỉ có ngay sau khi xác nhận; thẻ dựng lại từ dữ liệu đã lưu thì vắng, giao diện lấy
  lại qua `GET /v1/orders/{id}`.
- `eventTitle`, `eventSlug`, `sessionStartsAt`, `venueName`, `lines` vắng khi catalog không trả lời lúc
  dựng thẻ — thẻ vẫn thanh toán được.
- `status` có khi thẻ đến từ câu hỏi trạng thái ("mình thanh toán chưa"). Giao diện vẫn tự hỏi lại đơn
  mỗi 5 giây trong lúc chờ tiền.

## Chưa làm

- **Mã khuyến mãi**: không có trường `promotionCode`. Catalog từ chối mọi mã (`PricingQuery` trả
  `PROMOTION_INVALID` — "Khuyến mãi chưa tồn tại"), nên nhận mã trong chat là hứa một việc hệ thống không
  làm được.
- Thẻ chỉ lưu thẻ **đang mở** của phiên, không lưu thẻ của từng tin nhắn cũ: chip đã bấm hoặc đề xuất đã
  thay thì không còn ý nghĩa để hiện lại.
