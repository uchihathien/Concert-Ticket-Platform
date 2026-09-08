# Hướng thiết kế giao diện — v2

Thay thế [`docs/ui/design-direction.md`](../ui/design-direction.md) (palette lime trên nền tối). Sitemap, flow và states của v1 vẫn dùng được ở phần lớn; phần đổi ghi ở [§8](#8-những-gì-đổi-so-với-v1).

**Tham chiếu:** ticketbox.vn — lấy **cấu trúc thông tin và bố cục**, vốn là quy ước chung của ngành bán vé Việt Nam. **Không** lấy logo, hình ảnh, câu chữ, hay đúng mã màu thương hiệu của họ. NexaTicket phải nhận ra được là NexaTicket, không phải bản nhái.

---

## 1. Lấy gì từ mô hình ticketbox

| Pattern | Nội dung | Vì sao đúng |
| --- | --- | --- |
| **Header ưu tiên tìm kiếm** | Logo → **ô tìm kiếm rộng** → Tạo sự kiện → Vé của tôi → Ngôn ngữ → Đăng nhập | Người vào site bán vé thường đã biết muốn xem gì; tìm kiếm là hành vi số một |
| **Hero banner xoay vòng** | Băng rôn lớn full-width, 3–5 slide, tự chạy, có chấm điều hướng | Sự kiện lớn cần chỗ nổi bật; ban tổ chức trả tiền cho vị trí này |
| **Chip thể loại** | Nhạc sống · Sân khấu · Thể thao · Hội thảo · Khác — ngay dưới hero | Lọc một chạm, không cần vào trang lọc riêng |
| **Băng ngang cuộn ngang** | Nhiều dải: "Đang bán chạy", "Sắp diễn ra", "Cuối tuần này", theo thể loại | Duyệt nhanh trên điện thoại; mỗi dải là một câu chuyện biên tập |
| **Thẻ sự kiện gọn** | Ảnh 16:9 → tên (2 dòng) → ngày → địa điểm → **giá từ** | Bốn thông tin quyết định việc bấm vào |
| **Nút mua dính đáy** | Trang chi tiết có CTA cố định ở đáy trên mobile | Không phải cuộn ngược lên để mua |
| **Bottom nav mobile** | Khám phá · Vé của tôi · Tài khoản | Ba việc người dùng thật sự làm |
| **Ngôn ngữ vi/en** | Chuyển đổi ở header | vi-VN mặc định (PD-11) |

## 2. Không lấy

- Logo, wordmark, favicon, ảnh minh hoạ, biểu tượng thể loại của họ.
- Câu chữ marketing, tên mục, tên chiến dịch.
- Đúng bộ mã màu thương hiệu. Ta dùng **cùng họ màu** (đỏ ấm + vàng kim, nền sáng) nhưng giá trị riêng — xem §3.

## 3. Bảng màu

Đổi hệ so với v1: **nền sáng** cho customer/admin/platform (v1 là nền tối), giữ **nền tối** cho scanner.

```css
:root {
  /* Nền & chữ */
  --nt-bg:            #ffffff;
  --nt-bg-subtle:     #f7f5f3;   /* dải phân đoạn, nền thẻ */
  --nt-surface:       #ffffff;
  --nt-border:        #e6e2de;
  --nt-text:          #1a1614;
  --nt-text-muted:    #6b625c;

  /* Thương hiệu — đỏ ấm, KHÔNG trùng mã của bên nào */
  --nt-primary:       #c02a2a;
  --nt-primary-hover: #a32222;
  --nt-primary-ink:   #ffffff;
  --nt-accent:        #f2b705;   /* vàng kim: badge, nhấn phụ */
  --nt-accent-ink:    #2a1f00;

  /* Trạng thái */
  --nt-success:       #1f7a4d;
  --nt-warn:          #b06a00;
  --nt-danger:        #b3261e;

  /* Sơ đồ chỗ ngồi — phải phân biệt được trên nền sáng */
  --nt-seat-available:    #1f7a4d;
  --nt-seat-mine:         #c02a2a;
  --nt-seat-held-other:   #b8b2ac;
  --nt-seat-reserved:     #d99a00;
  --nt-seat-sold:         #d6d1cc;
  --nt-seat-blocked:      #efece9;   /* + gạch chéo */
  --nt-zone-standing:     #f2b70522; /* nền vùng đứng, viền --nt-accent */

  --nt-radius:      10px;
  --nt-radius-lg:   16px;
  --nt-focus:       0 0 0 2px #fff, 0 0 0 4px var(--nt-primary);
  --nt-font:        "Be Vietnam Pro", system-ui, sans-serif;
}
```

**Scanner giữ nền tối** (`--nt-bg: #14100f`, chữ sáng): dùng ngoài trời buổi tối ở cửa soát vé, nền tối đỡ chói và đỡ tốn pin. Đây là quyết định có lý do, không phải thiếu nhất quán — ghi rõ để không ai "thống nhất" nó về nền sáng.

Ba app còn lại **chốt một mode**, không có nút chuyển sáng/tối (giữ nguyên quyết định v1).

## 4. Bố cục trang chủ khách hàng

```text
┌──────────────────────────────────────────────────────┐
│ NexaTicket │ [🔍 Tìm sự kiện, nghệ sĩ…      ] │ Tạo SK │ Vé của tôi │ VI │ Đăng nhập │
├──────────────────────────────────────────────────────┤
│                                                      │
│            HERO BANNER (xoay vòng 3–5)               │
│                     ● ○ ○                            │
├──────────────────────────────────────────────────────┤
│ [Tất cả] [Nhạc sống] [Sân khấu] [Thể thao] [Hội thảo]│
├──────────────────────────────────────────────────────┤
│ Đang bán chạy                              Xem tất cả│
│ ┌────┐ ┌────┐ ┌────┐ ┌────┐  →  cuộn ngang           │
│ │ảnh │ │    │ │    │ │    │                          │
│ │Tên │ │    │ │    │ │    │                          │
│ │Ngày│ │    │ │    │ │    │                          │
│ │Từ ₫│ │    │ │    │ │    │                          │
│ └────┘ └────┘ └────┘ └────┘                          │
├──────────────────────────────────────────────────────┤
│ Sắp diễn ra                                Xem tất cả│
│ …                                                    │
├──────────────────────────────────────────────────────┤
│ Footer: Về NexaTicket · Điều khoản · Bảo mật · Hỗ trợ│
└──────────────────────────────────────────────────────┘

Mobile: hero full-width · chip cuộn ngang · mỗi dải cuộn ngang
        bottom nav: Khám phá · Vé của tôi · Tài khoản
```

Khác v1 rõ rệt: v1 chốt "first viewport chỉ có brand + 1 headline + 1 CTA, không card grid". Hướng mới đặt **nội dung lên trước thương hiệu** — đúng với site bán vé, nơi người dùng đến để tìm sự kiện chứ không để ngắm logo. Đây là thay đổi có chủ đích, ghi lại để không mâu thuẫn với acceptance criteria cũ.

## 5. Thẻ sự kiện — thứ tự thông tin cố định

```text
┌──────────────────┐
│   ảnh 16:9       │  ← lazy load, placeholder gradient
│  [badge nếu có]  │  ← "Sắp mở bán" / "Sắp hết vé" — dùng --nt-accent
├──────────────────┤
│ Tên sự kiện      │  ← tối đa 2 dòng, cắt bằng …
│ 📅 01/11/2026    │  ← suất gần nhất
│ 📍 Hà Nội        │
│ Từ 500.000₫      │  ← đậm, --nt-primary
└──────────────────┘
```

Bốn dòng, đúng thứ tự này ở mọi nơi thẻ xuất hiện. Không thêm mô tả, không thêm số lượt xem, không thêm nút chia sẻ trên thẻ.

## 6. Sơ đồ chỗ ngồi — phần khác biệt lớn nhất của v2

Site tham chiếu không có mô hình khu vực cố định/linh hoạt và vé ngồi/đứng lẫn lộn như ta. Phần này phải tự thiết kế ([venue-seating-model §12](venue-seating-model.md#12-ảnh-hưởng-lên-ui)).

```text
┌──────────────────────────────────────────┐
│ ← Tên sự kiện · Suất 19:00 · [04:59]     │
│ Còn được mua: 6 vé                       │  ← purchaseAllowance
├───────────────┬──────────────────────────┤
│ [Khán đài A]  │   ┌────────────────┐     │
│ [Khán đài B]  │   │  KHÁN ĐÀI A    │     │  ← ghế đánh số
│ [Sân đứng]    │   │  ▪▪▪▪ ▪▪▪▪     │     │
│ [VIP]         │   ├────────────────┤     │
│               │   │ SÂN TRUNG TÂM  │     │  ← vùng tô, viền vàng
│ ── Chú thích ─│   │ Vé đứng        │     │
│ ▪ Còn trống   │   │ còn 340/2.000  │     │
│ ▪ Bạn chọn    │   │   [−]  2  [+]  │     │  ← chọn số lượng
│ ▪ Người khác  │   └────────────────┘     │
│ ▪ Đã bán      │                          │
├───────────────┴──────────────────────────┤
│ A-12-07, A-12-08 + 2 vé đứng             │
│ Tổng 4.400.000₫       [ Giữ chỗ (4) ]    │
└──────────────────────────────────────────┘
```

Quy tắc bắt buộc:

1. **Chú thích đủ 6 trạng thái** ghế đánh số + 1 trạng thái vùng đứng.
2. Ghế bạn đang chọn (`--nt-seat-mine`) phải khác rõ ghế người khác giữ (`--nt-seat-held-other`).
3. Vùng đứng **không vẽ từng ô** — vẽ vùng + số chỗ còn lại + bộ tăng giảm.
4. `purchaseAllowance` hiện ngay đầu màn, không để khách chọn 8 chỗ rồi mới bị từ chối.
5. Không chỉ dựa vào màu: ghế đã bán có thêm hoạ tiết, vùng đứng có nhãn chữ.
6. Vùng chạm ≥ 44px trên mobile; ẩn bottom nav ở màn này.

## 7. Chuyển động

Ba hiệu ứng có mục đích, không hơn:

1. Chọn ghế: đổi màu + phóng nhẹ 150ms — phản hồi trước khi API xác nhận.
2. Đếm ngược: chuyển `--nt-warn` khi < 60s (giữ chỗ) hoặc < 2 phút (thanh toán).
3. Kết quả soát vé: chớp toàn màn 300ms rồi ổn định.

Không confetti, không parallax, không hero video tự phát có tiếng.

## 8. Những gì đổi so với v1

| | v1 | v2 |
| --- | --- | --- |
| Nền customer/admin | Tối (`#0c1210`) | **Sáng** (`#ffffff`) |
| Màu chính | Lime `#e8f56d` | **Đỏ ấm `#c02a2a`** + vàng kim `#f2b705` |
| Trang chủ | Brand hero, không card ở màn đầu | **Nội dung trước**: hero banner → chip → dải sự kiện |
| Header | Logo + nav | **Ô tìm kiếm rộng ở giữa** |
| Duyệt sự kiện | Danh sách dọc | **Dải cuộn ngang** theo chủ đề |
| Scanner | Tối | **Vẫn tối** — có lý do, không đổi |
| Sơ đồ chỗ | 6 trạng thái ghế | + **vùng đứng**, + hạn mức mua |
| App | 3 | **4** (thêm `web-platform`) |

Acceptance criteria của [`ui/screens/customer.md`](../ui/screens/customer.md) mục C-HOME (*"không có card grid ở first viewport"*) **không còn áp dụng**.

## 9. Tiếp cận

- Tương phản chữ/nền đạt WCAG AA; `--nt-primary` trên trắng đạt AA cho chữ ≥ 18px và cho nút.
- Focus ring `--nt-focus` ở mọi phần tử tương tác.
- Sơ đồ ghế có chế độ danh sách theo khu vực (Should) — canvas SVG khó cho trình đọc màn hình.
- Kết quả soát vé không chỉ dựa vào màu: chữ lớn + biểu tượng.
- Dải cuộn ngang phải điều khiển được bằng bàn phím và có nút "Xem tất cả" dẫn tới trang lọc đầy đủ.

## 10. Giọng văn

Tiếng Việt ngắn, tự nhiên: "Giữ chỗ 05:00", "Chuyển khoản đúng nội dung", "Vé đã check-in".
Lỗi = một câu cho người + mã ở dòng phụ: *"Ghế vừa được người khác giữ"* · `SEAT_UNAVAILABLE`.
