# NexaTicket — Frontend

4 app Next.js. Thiết kế: [`ui-direction.md`](../docs/architecture-v2/ui-direction.md) · Plan: [`plan/frontend.md`](../docs/architecture-v2/plan/frontend.md).

## Yêu cầu

Node 20+ · pnpm 9 (`corepack enable pnpm`).

## Chạy

```bash
pnpm install
pnpm dev          # chạy cả 4 app
pnpm build
pnpm typecheck
pnpm lint
```

Backend cần chạy sẵn để đăng nhập và gọi API — xem [`../backend/README.md`](../backend/README.md).

## Bốn app

| App | Cổng | Persona | Nền |
| --- | --- | --- | --- |
| `web-customer` | 3000 | Khách mua vé (mobile là kênh chính) | Sáng |
| `web-admin` | 3001 | Tổ chức | Sáng |
| `web-scanner` | 3002 | Nhân viên soát vé | **Tối** |
| `web-platform` | 3003 | Superadmin | Sáng |

`web-platform` tách khỏi `web-admin` vì ranh giới tài chính là ranh giới bảo mật: nó gọi deployable `finance`, có client OIDC riêng, và không nên chạy chung bundle với app mà tổ chức dùng.

**Scanner giữ nền tối có lý do**: dùng ngoài trời buổi tối ở cửa soát vé, nền tối đỡ chói và đỡ tốn pin. Đừng "thống nhất" nó về nền sáng.

## Packages

```text
packages/
  tokens/     CSS variables — palette v2 (nền sáng, đỏ ấm #c02a2a, vàng kim #f2b705)
  config/     tsconfig dùng chung
```

Sẽ thêm theo giai đoạn: `ui/` (component), `seatmap/` (renderer SVG dùng chung), `ts-sdk/` (client sinh từ OpenAPI), `auth/` (Auth.js).

`packages/seatmap` là điểm tái sử dụng quan trọng nhất: màn khách `C-SEATS` và màn xem trước của trình thiết kế chỗ ngồi vẽ **cùng một** cấu trúc dữ liệu. Viết hai lần là cầm chắc lệch nhau.

## Ba luật cứng

1. **Refresh token không bao giờ rời server.** Session ở cookie httpOnly do route handler quản lý.
2. **Access token không bao giờ chạm `localStorage`/`sessionStorage`** — chỉ giữ trong memory, lấy lại qua `/api/auth/token`.
3. **Sơ đồ chỗ ngồi cập nhật bằng DOM attribute, không qua React.** Delta WebSocket đổi `el.dataset.status`; màu do CSS lo. Không re-render 1.500 node cho mỗi delta.

## Hướng giao diện

Bố cục theo mô hình site bán vé Việt Nam: header ưu tiên tìm kiếm → hero xoay vòng → chip thể loại → dải sự kiện cuộn ngang → thẻ 4 dòng (ảnh, tên, ngày, giá từ).

Đổi hệ so với v1: **nền sáng thay cho nền tối**, đỏ ấm thay cho lime, và **nội dung đặt trước thương hiệu** trên trang chủ. Chi tiết và lý do: [`ui-direction.md`](../docs/architecture-v2/ui-direction.md).

## Trạng thái

Khung 4 app + design token. Màn hình thật dựng từ G1 theo [`plan/frontend.md §12`](../docs/architecture-v2/plan/frontend.md).
`web-customer` đã có khung bố cục trang chủ để chốt hướng thị giác.
