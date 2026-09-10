# Danh mục kiểm trước khi lên production

> Trang này là đích của thông báo lỗi mà `ProductionHardening` in ra khi một service từ chối khởi
> động với profile `prod`. Nếu bạn tới đây từ một dòng log: phần ngay dưới trả lời thẳng câu hỏi đó.

Danh mục này chỉ ghi những thứ **khác nhau giữa máy phát triển và production**. Những gì đúng ở cả
hai nơi thì đã nằm trong mã nguồn và trong `deploy/compose/prod.yml`, không lặp lại ở đây.

---

## Vì sao service không khởi động

`ProductionHardening` là một `EnvironmentPostProcessor`: nó chạy **trước** cả Flyway lẫn kết nối
database, nên khi cấu hình sai, thứ bạn thấy là thông báo về cấu hình chứ không phải
`Connection refused` — một triệu chứng dẫn người ta đi tìm sai chỗ hàng giờ.

Nó chặn đúng năm thứ, tất cả đều là "giá trị dev đi lạc lên production":

| Vấn đề | Sửa bằng |
| --- | --- |
| `nexaticket.internal.shared-secret` trống | `INTERNAL_SHARED_SECRET=$(openssl rand -base64 32)` |
| `super-admin-emails` còn `superadmin@nexaticket.local` | `SUPER_ADMIN_EMAILS` = email thật |
| Client secret Keycloak còn giá trị dev | `KEYCLOAK_ADMIN_CLIENT_SECRET` từ realm production |
| Issuer là `http://` | Dùng `https://`, hoặc khai `nexaticket.security.allow-plaintext-issuer=true` |
| Còn khoá `*.demo-data` / `*.demo.enabled` bằng `true` | Đặt `false` (compose đã khai sẵn) |

**`allow-plaintext-issuer` là một lối thoát có chủ đích, không phải một lỗ hổng.** Trong compose,
Keycloak nằm cùng mạng docker và TLS kết thúc ở ingress, nên issuer nội bộ là `http://`. Khai
tường minh biến này nghĩa là **ai đó đã quyết định** điều ấy an toàn trong kiến trúc mạng của họ —
khác hẳn với việc tắt cả chốt chặn để cho qua.

---

## 1. Bí mật

Sinh bằng `openssl rand -base64 32`, **mỗi mục một giá trị riêng**. Dùng lại một chuỗi cho nhiều
mục đích nghĩa là một lần lộ làm hỏng tất cả những mục đích đó cùng lúc.

- [ ] `INTERNAL_SHARED_SECRET` — cửa vào `/internal/**`. Trống thì `InternalApiFilter` **không được
      cắm vào** và mọi service gọi được endpoint nội bộ của nhau; `/internal/reservations` của
      inventory đổi trạng thái tồn kho mà không kiểm chủ sở hữu như đường công khai.
- [ ] `AUTH_SECRET_*` — **bốn giá trị khác nhau**. Dùng chung thì cookie phiên của app khách giải
      mã được ở app quản trị, và ranh giới giữa hai app chỉ còn là quy ước.
- [ ] `DB_PASSWORD_*` — mười một giá trị khác nhau. Xem §3.
- [ ] `KC_SECRET_*` — năm client secret của Keycloak.
- [ ] `PAYOS_CHECKSUM_KEY` — ký và kiểm chữ ký webhook. Sai khoá thì **mọi khoản tiền phải xác nhận
      tay** bằng lệnh reconcile.
- [ ] `.env` **không** nằm trong git. Kiểm bằng `git check-ignore -v deploy/compose/.env`.

## 2. Keycloak

- [ ] Dùng `nexaticket-realm.prod.json`, **không** phải bản dev — bản dev có bốn tài khoản mật khẩu
      bằng chính tên đăng nhập.
- [ ] `sslRequired: external`, `registrationAllowed: false`, `bruteForceProtected: true` (bản prod
      đã đặt sẵn; kiểm lại sau mỗi lần sửa realm bằng tay trên giao diện).
- [ ] Chạy bằng `start`, **không** `start-dev`, và trỏ vào `keycloak_db` trên volume. `start-dev`
      giữ database trong filesystem của container: recreate container là mất sạch tài khoản. Hậu
      quả không nhìn ra được từ phía Keycloak — `identity_db` nhận diện người dùng theo
      `idp_subject`, nên sau một lần recreate mọi tài khoản cũ đều lệch, đăng nhập vẫn qua nhưng
      **mọi lời gọi API trả 401/403** trong khi giao diện hiển thị là đã đăng nhập.
- [ ] `KC_PROXY_HEADERS=xforwarded`. Thiếu thì Keycloak dựng redirect URI bằng `http://` nội bộ và
      vòng đăng nhập gãy ở bước quay về, với thông báo không nói rõ lý do.
- [ ] SMTP thật. Không có thì **không ai đặt lại được mật khẩu**, kể cả khi quản trị viên bấm hộ.

## 3. Database

- [ ] **Mỗi service một user riêng** (ADR-1002). Có test tự động kiểm rằng user của service này
      không kết nối được vào database của service kia; dùng chung một user thì test đó đỏ, và một
      lỗ hổng ở service nhỏ nhất đọc được sổ cái.
- [ ] `deploy/compose/initdb-prod/01-databases.sh` **chỉ chạy một lần**, lúc thư mục dữ liệu còn
      rỗng. Đã có dữ liệu rồi thì đổi mật khẩu bằng `ALTER USER`; sửa file đó không có tác dụng gì.
- [ ] `max_connections=200`. Mặc định 100 **không đủ**: 11 service x pool 10, cộng psql và công cụ
      sao lưu. Service khởi động sau chết với `remaining connection slots are reserved`, một thông
      báo không hề gợi ý rằng nguyên nhân nằm ở cấu hình pool của service khác.
- [ ] **`ledger_db` có HAI role**: `ledger_owner` (sở hữu schema, chạy Flyway) và `ledger_app`
      (runtime). Đây là thứ làm cho sổ cái thật sự append-only: owner bỏ qua mọi `REVOKE` trên bảng
      của chính mình, nên chạy service bằng owner khiến `UPDATE postings` và
      `DELETE FROM journal_entries` đi qua trót lọt trong khi migration vẫn có dòng `REVOKE` và
      tài liệu vẫn nói là đã chặn. Kiểm bằng `LedgerAppendOnlyIT`, và kiểm lại sau mỗi lần khôi
      phục database — `pg_restore` gán lại quyền sở hữu theo user chạy lệnh.
- [ ] Sao lưu đã chạy và đã **thử phục hồi** — xem §7.

## 4. Mạng và biên

- [ ] Chỉ `api-gateway`, bốn app Next và Keycloak mở cổng. Mười hai service backend **không publish
      gì**, kể cả cổng quản trị 9090 — đó chính là thứ làm cho `/actuator/prometheus` không cần
      token mà vẫn không lộ ra internet.
- [ ] `NEXATICKET_GATEWAY_TRUSTEDPROXYCOUNT` đếm **đúng** số proxy đứng trước gateway. Đếm thiếu
      thì kẻ tấn công tự khai `X-Forwarded-For` và vô hiệu hoá rate limit; đếm thừa thì mọi IP nhìn
      ra như IP của proxy, và cả internet dùng chung một hạn mức.
- [ ] `NEXATICKET_GATEWAY_ALLOWEDORIGINS` liệt kê tường minh bốn origin, không dùng `*`.
- [ ] `NEXATICKET_REALTIME_ALLOWEDORIGINS` cũng vậy. **WebSocket không có preflight** để chặn, nên
      danh sách này là chốt chặn duy nhất — để nguyên mặc định `localhost:3000` nghĩa là mọi trang
      web đều mở được socket tới đây.
- [ ] TLS ở ingress cho cả năm tên miền công khai.

## 5. Frontend

- [ ] `REDIS_URL` cho cả bốn app. Thiếu thì app **từ chối khởi động** — cố ý: bản in-memory chỉ
      sống trong một tiến trình, và Next tái tạo tiến trình bất cứ lúc nào, nên hậu quả là người
      dùng bị đăng xuất ngẫu nhiên, không theo quy luật nào, và không có log nào giải thích.
- [ ] `NEXT_PUBLIC_API_BASE_URL` được **nhúng lúc build**, không đọc lúc chạy. Một image không dùng
      lại được giữa staging và production nếu địa chỉ API khác nhau — phải build riêng mỗi môi trường.
- [ ] Redis chạy `--appendonly yes`. Bản dev thuần bộ nhớ; giữ nguyên cấu hình đó ở production
      nghĩa là mọi người dùng bị đăng xuất cùng lúc sau mỗi lần khởi động lại Redis — kể cả lần
      khởi động lại theo lịch để vá bảo mật.

## 6. Quan sát

- [ ] Prometheus scrape cổng **9090** của từng service (trong mạng nội bộ), không phải 8080.
- [ ] Trace: `TRACING_ENABLED=true` **chỉ khi** đã có collector nghe ở `OTEL_ENDPOINT`. Không có ai
      nhận thì exporter thử gửi mỗi vài giây và lấp đầy log bằng lỗi kết nối, che mất dòng đáng đọc.
- [ ] `OTEL_SAMPLE_RATE` là tỷ lệ **lấy mẫu**, không phải công tắc. `1.0` ở lưu lượng thật là rất
      nhiều dữ liệu và rất nhiều tiền lưu trữ; `0.1` đủ để thấy hình dạng của độ trễ. Lỗi thì không
      cần lấy mẫu — chúng đã có log kèm `correlationId`.
- [ ] Log giữ được `correlationId`. Nó **đi xuyên mọi service** kể từ `CorrelationPropagation`; đó
      là thứ nối bốn dòng log ở bốn service về cùng một hành động của cùng một người.

## 7. Sao lưu và phục hồi

- [ ] `deploy/scripts/backup-databases.sh` chạy theo lịch (cron hoặc systemd timer).
- [ ] **Đã thử phục hồi ít nhất một lần**, vào một database rỗng, và đối chiếu số bản ghi.
      Một bản sao lưu chưa từng được phục hồi không phải là bản sao lưu — nó là một file.
- [ ] Bản sao lưu nằm **ngoài** máy chủ đang chạy. Cùng đĩa với database nghĩa là cùng chết.
- [ ] `ledger_db` giữ lâu hơn phần còn lại: đó là sổ cái tiền, và nghĩa vụ đối soát kéo dài hơn
      nhiều so với nhu cầu vận hành (`custodial-funds.md`).

---

## Việc còn lại, đã biết và cố ý chưa làm

Ghi ở đây để không ai phải đi tìm lại:

- **Redis chết thì hệ thống hành xử ra sao** — đã quyết định và ghi ở
  [`ADR-1015`](../adr/ADR-1015-redis-fail-mode.md). Đọc trước khi chỉnh bất cứ thứ gì liên quan tới
  rate limit hoặc giữ chỗ.
- **Xoá cache thành viên theo sự kiện** (thay vì chờ TTL): thu hồi quyền hiện có độ trễ tối đa bằng
  `membership-cache-ttl`. Chấp nhận được, nhưng không phải mãi mãi.
- **Kiểm dữ liệu bằng Zod ở biên frontend**: hiện tin vào kiểu TypeScript, vốn biến mất lúc chạy.
