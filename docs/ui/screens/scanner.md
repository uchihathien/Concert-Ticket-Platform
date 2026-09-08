# Web screens — Scanner (`web-scanner`)

App tối giản cho điện thoại staff. Palette dark; không marketing chrome. Online-only (ADR-0014).

---

## S-LOGIN — Đăng nhập scanner

| | |
| --- | --- |
| **Route** | `/login` |
| **Auth** | Public |
| **Milestone** | 04 |

### Mục đích

OIDC cho `CHECKIN_STAFF`+ thuộc org.

### Layout

```text
┌────────────────────────┐
│ NexaTicket Scan        │
│ [Đăng nhập]            │
└────────────────────────┘
```

### States

| Không đủ role | “Tài khoản không có quyền check-in” |
| Success | → S-HOME |

### Acceptance

- [ ] CUSTOMER thuần bị từ chối

---

## S-HOME — Chọn suất + quét

| | |
| --- | --- |
| **Route** | `/` (hoặc `/scan` sau khi chọn session) |
| **Auth** | CHECKIN_STAFF+ |
| **Milestone** | 04 |

### Mục đích

Chọn event/session đang làm việc và quét QR liên tục.

### Layout — chưa chọn session

```text
┌────────────────────────────────┐
│ NexaTicket Scan · Online ●     │
│ User · Logout                  │
├────────────────────────────────┤
│ Chọn sự kiện                   │
│ [Event dropdown]               │
│ Chọn suất                      │
│ [Session dropdown]             │
│ [Bắt đầu quét]                 │
└────────────────────────────────┘
```

### Layout — đang quét

```text
┌────────────────────────────────┐
│ ← Đổi suất   Hòa Âm · 19:00    │
│ Online ●                       │
├────────────────────────────────┤
│                                │
│      Camera viewfinder         │
│      (góc QR guide)            │
│                                │
├────────────────────────────────┤
│ Hoặc dán mã vé                 │
│ [________________] [Kiểm tra]  │
└────────────────────────────────┘
```

### Thành phần

| Element | Spec |
| --- | --- |
| Online indicator | Xanh = online; đỏ = offline disable submit |
| Camera | Rear preferred; permission prompt copy rõ |
| Torch | Should |
| Manual input | Luôn có |
| Session sticky | `sessionStorage` đến logout |

### Tương tác

1. Decode QR → `POST /v1/check-ins` `{ qrToken }`
2. Lock UI đến response
3. Hiện S-RESULT overlay
4. Dismiss → sẵn sàng quét tiếp (cooldown 500ms)
5. Offline: banner “Cần mạng — không quét offline”; stop camera submit

### States

| State | UI |
| --- | --- |
| Chưa chọn session | Empty copy “Chọn suất diễn để bắt đầu quét” |
| Camera denied | Manual-only + hướng dẫn mở Settings |
| API 5xx | Overlay lỗi tạm + retry |
| Mutation pending | Freeze viewfinder overlay mờ |

### API

- List events/sessions org mà staff được check-in
- `POST /v1/check-ins`

### Copy

- “Đưa mã QR vào khung hình”
- “Cần mạng — MVP không hỗ trợ quét offline”

### Responsive / a11y

- Portrait lock
- Notch safe area
- Kết quả không chỉ màu — text lớn

### Acceptance

- [ ] Happy path &lt; 2s cảm nhận trên mạng tốt
- [ ] Không double-submit một QR
- [ ] Đổi suất được

---

## S-RESULT — Overlay kết quả

| | |
| --- | --- |
| **Surface** | Overlay full-bleed trên S-HOME |
| **Auth** | — |
| **Milestone** | 04 |

### Mục đích

Feedback tức thì cho cửa soát vé.

### Layout

```text
┌────────────────────────────────┐
│████████████████████████████████│
│█                              █│
│█     HỢP LỆ / ĐÃ CHECK-IN /   █│
│█     KHÔNG HỢP LỆ             █│
│█     Ghế A-1-01               █│
│█     Event short              █│
│█     (chi tiết phụ)           █│
│█     Chạm để quét tiếp        █│
│█                              █│
│████████████████████████████████│
└────────────────────────────────┘
```

### Variants

| Result | Nền / icon | Text chính | Phụ |
| --- | --- | --- | --- |
| `CHECKED_IN` | `--nt-success` | HỢP LỆ | Seat + event |
| `ALREADY_CHECKED_IN` | `--nt-warn` | ĐÃ CHECK-IN | Giờ check-in trước + seat |
| `INVALID_TOKEN` | `--nt-danger` | KHÔNG HỢP LỆ | “Mã không hợp lệ / hết hạn” |
| `TICKET_NOT_VALID` | `--nt-danger` | KHÔNG HỢP LỆ | “Vé hủy / hoàn tiền” |
| `WRONG_ORGANIZATION` | `--nt-danger` | SAI SỰ KIỆN | “Vé không thuộc tổ chức này” |
| `EVENT_NOT_OPEN` | `--nt-warn` | CHƯA MỞ CỬA | Optional |

### Tương tác

- Auto dismiss 2.5s **hoặc** tap ngay
- Sound: success beep vs fail beep (Should; cần user gesture)
- Không dùng confetti

### Motion

Flash 300ms opacity/scale rồi settle (design-direction).

### Acceptance

- [ ] Rescan cùng vé → ALREADY, không tạo check-in mới
- [ ] Staff đọc được kết quả ngoài trời (chữ lớn, contrast cao)
- [ ] Tap dismiss không submit lại QR cũ
