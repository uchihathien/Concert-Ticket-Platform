# Payment VietQR (mobile)

## Mục tiêu UX

User chuyển khoản đúng **số tiền + nội dung** trong 15 phút trên điện thoại, không cần desktop.

## Layout PaymentQr

```text
┌────────────────────────────────┐
│ Thanh toán · 14:32             │
├────────────────────────────────┤
│ Số tiền    3.000.000₫   [Copy] │
│ Nội dung   NTX9F2K1     [Copy] │
│ NH VCB · ****5678              │
│ Chủ TK: CONG TY ABC            │
├────────────────────────────────┤
│         ┌──────────┐           │
│         │ VietQR   │           │
│         │  image   │           │
│         └──────────┘           │
│  Mở app ngân hàng quét QR      │
├────────────────────────────────┤
│ Trạng thái: Đang chờ…          │
│ [Tôi đã chuyển — kiểm tra]     │
│ [Lưu ảnh QR] (Should)          │
└────────────────────────────────┘
```

## Hành vi

| Action | Behavior |
| --- | --- |
| Copy amount / reference | Clipboard + toast “Đã sao chép” |
| Poll | Mỗi 5–8s GET order; exponential backoff khi background (RN: khi active) |
| Tôi đã chuyển | Force poll ngay |
| PAID | Navigate Success → Ticket |
| EXPIRED | Full state hết hạn |
| MANUAL_REVIEW | Không hiện vé; copy đối soát |
| App background | Giữ countdown theo `paymentExpiresAt`; không pause TTL server |
| Banking app | User tự mở (VietQR phổ biến); **không** integrate SDK từng bank MVP |

## RN extras

- `VietQrCard`: render từ `vietQr.payload` (library QR) hoặc URL ảnh từ API nếu có.
- Save to camera roll: permission photo; Only Should.
- Prevent accidental back: confirm “Bạn chưa hoàn tất chuyển khoản?”.
- Secure flag / FLAG_SECURE Android trên màn QR vé (TicketDetail) — Should; PaymentQr không bắt buộc.

## Mobile web extras

- `viewport` không zoom nhầm khi tap input.
- Hướng dẫn 3 bước ngắn phía dưới QR (mở NH → quét → đúng nội dung).

## Errors

| Case | Copy |
| --- | --- |
| Sai tiền (user) | Không hiện trên client đến khi MANUAL_REVIEW/email |
| Mất mạng lúc poll | Banner offline; CTA thử lại |
| Order không thuộc user | 403 màn lỗi |
