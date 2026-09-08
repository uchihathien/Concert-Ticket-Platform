# Scanner trên điện thoại (mobile web)

Staff dùng `web-scanner` trên điện thoại — **Must** cho go-live; native scanner app = later.

## Flow

Xem thêm [../flows/check-in.md](../flows/check-in.md).

```text
Login → chọn Event/Session → Camera full screen → overlay kết quả
```

## Mobile-specific

| Hạng mục | Spec |
| --- | --- |
| Camera | `getUserMedia` rear camera; fallback list devices |
| Torch | Should nếu browser hỗ trợ |
| Orientation | Portrait lock |
| Safe area | Viewfinder không bị notch che góc QR |
| Manual entry | Luôn có ô dán token nếu camera fail |
| Double scan | Lock UI đến khi API trả; cooldown 500ms |
| Sound | Short beep success / different fail (user gesture unlock audio) |
| Offline | Banner dừng — ADR-0014 |

## Session sticky

- Ghi `sessionId` đã chọn vào `sessionStorage` để mở lại PWA không hỏi lại đến khi logout.
- Đổi suất: menu top.

## QA devices

Ít nhất: 1 iPhone Safari, 1 Android Chrome tầm trung (common tại venue).
