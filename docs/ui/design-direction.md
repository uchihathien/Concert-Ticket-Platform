> ⚠️ **Thay thế bởi [../architecture-v2/ui-direction.md](../architecture-v2/ui-direction.md).**
> v2 dùng nền sáng + đỏ ấm thay cho nền tối + lime, và bố cục trang chủ đặt nội dung trước thương hiệu.
> Riêng scanner vẫn giữ nền tối.

# Design direction (MVP)

Hướng visual để FE thống nhất. Không dùng Figma tokens bắt buộc — định nghĩa CSS variables trong `packages` hoặc mỗi app.

## Brand

- Tên sản phẩm **NexaTicket** là tín hiệu hero trên customer home và login — không chỉ chữ nhỏ trên nav.
- Tone: sự kiện đêm Việt Nam — rõ ràng, năng lượng, tin cậy thanh toán; không “SaaS tím mặc định”.

## Visual tokens (đề xuất)

```css
:root {
  --nt-bg: #0c1210;
  --nt-bg-elevated: #141c18;
  --nt-surface: #1a2420;
  --nt-text: #f2f5f0;
  --nt-text-muted: #9aa89c;
  --nt-accent: #e8f56d;      /* lime ticket stub — CTA chính */
  --nt-accent-ink: #12180f;
  --nt-danger: #ff6b5a;
  --nt-success: #3dd68c;
  --nt-warn: #ffb020;
  --nt-held-mine: #e8f56d;
  --nt-held-other: #4a5560;
  --nt-reserved: #ffb020;
  --nt-sold: #2a3330;
  --nt-available: #2d6a4f;
  --nt-font-display: "Be Vietnam Pro", sans-serif; /* hoặc equivalent expressive VN-ready */
  --nt-font-body: "Be Vietnam Pro", sans-serif;
  --nt-radius: 12px;
  --nt-focus: 0 0 0 2px var(--nt-bg), 0 0 0 4px var(--nt-accent);
}
```

**Admin / Scanner:** cùng palette; admin nền hơi sáng hơn optional (`--nt-bg: #f4f6f3`, text dark) nếu team muốn đọc bảng lâu — **chốt một mode per app**, không theme toggle MVP.

**Tránh:** purple-indigo gradient mặc định; cream+#terracotta serif; broadsheet hairline; glow tím; pill cluster stats trên hero.

## Typography

| Role | Dùng |
| --- | --- |
| Display | Tên event, NexaTicket trên home |
| Title | H1 màn |
| Body | 16px+ |
| Mono | `paymentReference`, seat code |

## Layout rules

1. Customer home first viewport: brand + 1 headline + 1 câu + 1 CTA group + 1 full-bleed atmosphere (ảnh sự kiện/venue) — không stats, không “this week” cards chồng.
2. List/detail mới dùng danh sách sự kiện — không card-shadow dày nếu không cần tương tác.
3. Seat map: canvas full width; legend cố định; tray dưới (mobile) cho ghế đang chọn + CTA.
4. Checkout pay: QR lớn trung tâm; reference + amount sticky; không nav phụ.

## Motion (2–3 intentional)

1. Seat select: scale/color 150ms — phản hồi tức thì trước khi API confirm.
2. Countdown urgency: màu → warn khi &lt; 60s hold / &lt; 2 phút payment.
3. Check-in result: full-screen success/fail flash 300ms rồi settle (scanner đọc nhanh).

Không confetti, không parallax nặng.

## Seat legend (bắt buộc trên C-SEATS)

| State | Màu token | Tương tác |
| --- | --- | --- |
| AVAILABLE | `--nt-available` | Chọn được |
| HELD (mine) | `--nt-held-mine` | Bỏ chọn / vào hold summary |
| HELD (other) | `--nt-held-other` | Disabled |
| RESERVED | `--nt-reserved` | Disabled |
| SOLD | `--nt-sold` | Disabled |
| BLOCKED | muted pattern | Disabled |

## Accessibility

- Contrast CTA trên dark đạt WCAG AA.
- Focus ring `--nt-focus`.
- Scanner result không chỉ dựa vào màu — có text lớn + icon.
- Seat map: hỗ trợ chọn bằng list/section filter nếu canvas khó a11y (Should).

## Copy giọng điệu

- Ngắn, tiếng Việt tự nhiên: “Giữ ghế 05:00”, “Chuyển khoản đúng nội dung”, “Vé đã check-in”.
- Lỗi: câu người + mã (`SEAT_UNAVAILABLE` — Ghế vừa được người khác giữ).
