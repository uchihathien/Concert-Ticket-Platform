# Seat map UX (mobile)

Màn critical nhất trên điện thoại. Áp dụng C-SEATS (web) và `SeatMap` (RN).

## Layout

```text
┌────────────────────────────────┐
│ ← Event short · Suất 19:00     │
│ Giữ ghế: 04:59     (nếu hold)  │
├────────────────────────────────┤
│ Section chips: A B C VIP       │
├────────────────────────────────┤
│                                │
│         Seat canvas            │
│      (pan / pinch zoom)        │
│                                │
├────────────────────────────────┤
│ ■ Trống ■ Tôi giữ ■ Người khác │
│ ■ Đã đặt ■ Đã bán              │
├────────────────────────────────┤
│ Đã chọn 2 · 3.000.000₫         │
│ [      Giữ ghế (2)      ]      │
└────────────────────────────────┘
```

## Canvas

| Hạng mục | Spec |
| --- | --- |
| Seat hit target | ≥ 44×44 pt khi zoom mặc định section |
| Default view | Fit selected section hoặc whole map nếu &lt; 200 seats |
| Pan | 1 finger |
| Zoom | Pinch 0.5×–3×; double-tap zoom to seat (Should) |
| Performance | RN: ưu tiên Skia / react-native-svg + virtualize nếu &gt; 500 seats; web: canvas hoặc SVG |
| Update | WS delta đổi màu seat; không remount toàn map |

## Selection

1. Tap AVAILABLE → selected (accent outline).
2. Tap lại → bỏ chọn.
3. Tap HELD(other)/RESERVED/SOLD/BLOCKED → haptic nhẹ + toast 1 dòng.
4. Max `MAX_SEATS_PER_HOLD` (8): toast “Tối đa 8 ghế / lần giữ”.
5. CTA disabled nếu selection rỗng.

## Hold

- CTA → POST holds (idempotency key UUID/session).
- Success: countdown 5p trên header; CTA đổi “Tiếp tục” → HoldSummary **hoặc** bottom sheet summary trên cùng SeatMap (chốt RN: navigate HoldSummary để rõ).
- 409 SEAT_UNAVAILABLE: bỏ chọn ghế lỗi, refetch seats, toast.
- 503 REDIS: toast retry, giữ selection.

## Realtime

| Event | UI |
| --- | --- |
| WS message version = local+1 | Apply changes |
| version gap | Full refetch GET seats |
| WS disconnect | Banner “Đang cập nhật chậm”; poll 10s |
| Seat mình HELD bị conflict (hiếm) | Force release UI + message |

## Accessibility

- Section list mode: danh sách ghế AVAILABLE theo hàng (toggle “Danh sách”) cho VoiceOver/TalkBack khi canvas khó.
- Announce countdown mỗi 60s (Should).

## Orientation

- Lock **portrait** trên SeatMap và Payment (tránh vỡ QR/canvas). Landscape = out MVP.
