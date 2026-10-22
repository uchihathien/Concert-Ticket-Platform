# Product Brief: NexaTicket

**Ngày:** 2026-10-22
**Tác giả:** uchihathien (soạn cùng Claude, vai Analyst)
**Trạng thái:** Draft, chờ xác nhận
**Phiên bản:** 0.1
**Loại dự án:** Brownfield. Code đã chạy production tại `concertth.site`.

> Nguồn: `docs/` (SRS, RBAC, ADR, success-metrics, DELIVERY-PLAN-3M, BRAINSTORM, architecture-v2), code trong `backend/` và `frontend/`, lịch sử git. Phần ghi **[Giả định]** là suy luận, chưa có người xác nhận.

---

## 1. Tóm tắt (Executive Summary)

NexaTicket là nền tảng bán vé sự kiện cho thị trường Việt Nam, theo mô hình nhiều tenant: mỗi nhà tổ chức là một tenant tách biệt. Khách được chọn đúng ghế trên sơ đồ, theo thời gian thực. Thanh toán qua payOS (chuyển khoản/QR, xác nhận bằng webhook có ký). Vé là mã QR có chữ ký, soát vé online.

Phần kỹ thuật đã đi xa: 13 service Spring Boot, 4 web app và 4 app mobile, đã deploy lên một EC2. Thứ còn thiếu để thành **sản phẩm thương mại** nằm ở chỗ khác:
- một mô hình giữ tiền hợp pháp,
- kiểm thử đầu-cuối cho luồng tiền,
- vận hành gọn vừa sức 2 người,
- và nhà tổ chức thật đầu tiên.

**Ý chính:**
- **Vấn đề:** nhà tổ chức nhỏ và vừa ở Việt Nam thiếu công cụ tự đăng sự kiện có sơ đồ ghế. Khách mua vé không chắc ghế mình chọn có thật hay không.
- **Giải pháp:** nhà tổ chức tự đăng sự kiện, khách chọn ghế realtime và trả qua QR ngân hàng, vé QR được soát online.
- **Người dùng:** khách mua vé, nhà tổ chức (chủ/quản lý/soát vé), quản trị nền tảng.
- **Chỉ số chính:** số ghế thanh toán thành công mỗi tuần, với điều kiện 0 ghế bán trùng và 0 lệch đối soát.

---

## 2. Vấn đề (Problem Statement)

### Vấn đề

Bán vé có chỗ ngồi cho sự kiện đông người có bốn cái khó cùng lúc (`docs/BRAINSTORM.md` §2):
1. Không được bán trùng ghế khi hàng nghìn người cùng giành một suất diễn.
2. Thanh toán chuyển khoản là bất đồng bộ, dễ ra vé mà không có tiền hoặc có tiền mà không ra vé.
3. Dữ liệu tài chính của các nhà tổ chức phải tách biệt tuyệt đối.
4. Phải đẩy trạng thái ghế realtime cho rất nhiều người xem.

### Ai gặp vấn đề

**Người dùng chính:**
- Nhà tổ chức sự kiện nhỏ và vừa (concert, sân khấu, hội thảo) muốn tự bán vé có sơ đồ ghế.
- Khách mua vé muốn chọn đúng ghế và nhận vé ngay sau khi trả tiền.

**Người dùng phụ:**
- Nhân viên soát vé tại cửa.
- Quản trị nền tảng: tạo/khóa tổ chức, đối soát, chi trả.

### Hiện trạng

**Cách đang làm:** **[Giả định]** bán qua Ticketbox (phí cao, phải duyệt), qua Google Form kèm chuyển khoản rồi đối soát tay, hoặc bán vé giấy.

**Điểm đau:**
- Đối soát chuyển khoản bằng tay, dễ sót, dễ trùng.
- Không có sơ đồ ghế, nên phải xếp chỗ tay và hay tranh chấp chỗ.
- Soát vé giấy hay vé ảnh chụp dễ bị làm giả hoặc dùng hai lần.

### Tác động và mức cấp bách

**Nếu không giải quyết:** nhà tổ chức mất doanh thu vì bán trùng hoặc hoàn tiền, khách mất niềm tin. Với nền tảng thì một sự cố tiền là đủ mất uy tín.

**Tại sao lúc này:** **[Giả định, cần bạn xác nhận]** thanh toán QR ngân hàng (VietQR/payOS) đã phổ biến, phí thấp và có webhook tức thì, nên dễ làm luồng mua tự động. Docs chưa ghi lý do thị trường.

**Tần suất:** mỗi lần mở bán. Áp lực dồn vào vài phút đầu.

---

## 3. Người dùng mục tiêu (Target Users)

### Persona (`docs/00-discovery/srs.md` §2, `rbac-permission-matrix.md`)

#### Persona 1: Khách mua vé (`CUSTOMER`)
- **Vai trò:** người mua vé qua web trên điện thoại.
- **Mục tiêu:** chọn ghế, trả tiền và có vé trong ≤10 phút.
- **Điểm đau:** sợ ghế đã chọn bị người khác lấy, sợ chuyển tiền xong không có vé.
- **Mức kỹ thuật:** phổ thông, quen quét QR ngân hàng.
- **Cách dùng:** dồn vào lúc mở bán, sau đó mở lại để xem vé.

#### Persona 2: Nhà tổ chức (`ORG_OWNER`, `ORG_ADMIN`, `EVENT_MANAGER`)
- **Vai trò:** tạo địa điểm, sơ đồ ghế, sự kiện, hạng vé, rồi theo dõi doanh thu.
- **Mục tiêu:** đăng một sự kiện xong trong một buổi làm việc.
- **Điểm đau:** vẽ sơ đồ ghế tốn công, không biết bao giờ nhận tiền.
- **Mức kỹ thuật:** trung bình.
- **Cách dùng:** dùng nhiều trước sự kiện, theo dõi doanh số mỗi ngày.

#### Persona 3: Nhân viên soát vé (`CHECKIN_STAFF`)
- Quét QR, có kết quả trong ≤2 giây, mỗi vé chỉ vào được một lần.

#### Persona 4: Quản trị nền tảng (`SUPER_ADMIN`)
- Tạo và khóa tổ chức, xem sổ cái, duyệt chi trả theo nguyên tắc hai người (four-eyes), xử lý hoàn tiền.

### Nhu cầu

**Bắt buộc (MVP):**
- Chọn ghế realtime, không bán trùng.
- Thanh toán payOS có tự xác nhận, vé QR có chữ ký.
- Nhà tổ chức tự đăng sự kiện và xem doanh thu.
- Soát vé online, chặn dùng lại vé.

**Nên có:**
- Email gửi vé và nhắc lịch.
- Mã giảm giá đơn giản.
- Bảng doanh thu theo hạng vé.

**Để sau (Future):**
- App mobile hoàn chỉnh. Hiện mobile-organizer và mobile-platform mới có 1–2 màn hình.
- Chatbot AI cho khách. Đã có code, nhưng ngoài phạm vi MVP theo PD-12.
- Soát vé offline, gợi ý sự kiện, dự báo, giá động.

---

## 4. Giải pháp đề xuất (Proposed Solution)

### Tổng quan

Nền tảng web, ưu tiên điện thoại, gồm 4 bề mặt dùng chung một bộ API qua gateway:
- web khách hàng,
- web nhà tổ chức,
- web soát vé,
- web quản trị.

Backend theo DDD và kiến trúc hexagonal. Mỗi bounded context có database riêng, các service nói chuyện qua outbox và RabbitMQ.

### Năng lực chính (đã có trong code)

1. **Giữ ghế chống bán trùng**
   - Mô tả: chặn hai lớp. Lớp một là script Lua trên Redis (`RedisAvailabilityGate`). Lớp hai là unique index trên Postgres làm chốt chặn cuối. Có `SeatHoldConcurrencyIT` và `OversellBackstopIT`.
   - Giá trị: khách tin ghế mình chọn là của mình, nhà tổ chức không phải hoàn tiền vì trùng ghế.
2. **Thanh toán và đối soát tự động**
   - Mô tả: tạo link payOS, kiểm chữ ký webhook (`PayosSignature`), saga đặt hàng có bù trừ, sổ cái kép chỉ ghi thêm.
   - Giá trị: không còn đối soát tay, tiền và vé luôn khớp nhau.
3. **Vé QR có chữ ký và soát vé**
   - Mô tả: vé là JWS ký Ed25519 và có `jti` duy nhất. Có nhật ký check-in. Máy quét chạy trên web, dùng `BarcodeDetector`.
   - Giá trị: chặn vé giả và vé dùng hai lần.
4. **Nhà tổ chức tự phục vụ**
   - Mô tả: trình vẽ sơ đồ ghế, mẫu concert, tạo phiên và hạng vé, đăng/gỡ sự kiện, bảng doanh thu.

### Giá trị khác biệt

Giao vé đúng ghế ngay khi tiền về, không bán trùng kể cả lúc cao điểm. Nhà tổ chức tự đăng, không chờ duyệt. **[Giả định]** Phí thấp hơn Ticketbox.

### Phạm vi MVP

**Ra mắt cùng MVP:**
- Ba web: khách hàng, nhà tổ chức, soát vé. Thêm web quản trị tối thiểu.
- Luồng tiền payOS với đối soát tự động.
- Email gửi vé.

**Để sau (Deferred):**
- App mobile (theo thứ tự cắt giảm trong `DELIVERY-PLAN-3M.md`, RN không chặn go-live).
- Chi trả tự động hàng loạt. Giai đoạn đầu chi trả thủ công có ghi sổ.
- Chatbot AI. Chỉ bật với knowledge base đã duyệt, không đụng tới checkout.

---

## 5. Mục tiêu và ràng buộc (Goals & Constraints)

### Mục tiêu kinh doanh
- Ra mắt thương mại có kiểm soát (soft launch) trong **4 tuần**, với 1–2 nhà tổ chức thí điểm.
- Bán thành công ít nhất một sự kiện thật mà không có sự cố tiền nào.
- **[Cần quyết]** Chốt mô hình doanh thu: phí % trên vé, phí cố định, hay cả hai.

### Ràng buộc

**Không thương lượng (`docs/00-discovery/product-decisions.md`):**
- Không bán trùng ghế. Server là nguồn sự thật.
- Phạm vi tenant lấy từ membership đã xác thực.
- Webhook, thanh toán và phát vé đều idempotent.
- QR không chứa dữ liệu cá nhân.
- AI không nằm trong luồng checkout.
- **Pháp lý tiền:** mô hình escrow v2 (nền tảng giữ tiền rồi chi trả lại) có thể cần giấy phép trung gian thanh toán theo Nghị định 52/2024 (`architecture-v2/custodial-funds.md` §9). **Đây là rủi ro chặn ra mắt thương mại.**
- Chỉ dùng VND. Lưu dữ liệu đơn hàng ≥5 năm.

**Ràng buộc kỹ thuật và nguồn lực:**
- Nhóm 2 người, hạn dưới 1 tháng.
- Hạ tầng: 1 EC2 chạy 26 container. Một lỗi máy chủ là cả hệ thống dừng.
- Hold 5 phút, cửa sổ thanh toán 15 phút. Mỗi lần giữ tối đa 8 ghế, mỗi khách tối đa 10 vé một suất.

---

## 6. Chỉ số thành công (Success Metrics)

### Chỉ số chính

**Tính toàn vẹn tiền và ghế**
- Baseline: chưa có số liệu bán thật.
- Target: 0 ghế bán trùng, 0 đơn lệch giữa payOS, đơn hàng, vé và sổ cái.
- Timeline: từ ngày bán đầu tiên.
- Đo bằng: job đối soát hằng ngày và cảnh báo khi lệch khác 0.

**Chuyển đổi mua vé**
- Baseline: chưa có.
- Target: hold → paid ≥40%, lượt xem → mua ≥60% (`success-metrics.md`). Thời gian từ lúc chọn ghế tới khi có vé, trung vị ≤10 phút.
- Timeline: sự kiện thí điểm đầu tiên.
- Đo bằng: read model analytics cùng event taxonomy.

**Soát vé tại cửa**
- Baseline: chưa có.
- Target: ≥98% vé qua ngay lần quét đầu. p95 phản hồi ≤2 giây.
- Timeline: ngày diễn ra sự kiện thí điểm.
- Đo bằng: log check-in.

**Hiệu năng**
- Target: p95 giữ ghế ≤300 ms ở mức tải thí điểm. **[Giả định]** 1.000 người cùng lúc; mốc 10.000 dời sang sau ra mắt.

### Tầm nhìn thành công
- **1 tháng:** 1–2 nhà tổ chức thí điểm, ít nhất 1 sự kiện bán thật, 0 sự cố tiền.
- **3 tháng:** 5–10 nhà tổ chức. Mô hình giữ tiền hợp pháp đã chốt. Load test 10k đạt.
- **12 tháng:** **[Cần bạn đặt]** mục tiêu GMV và số nhà tổ chức.

---

## 7. Thị trường và cạnh tranh (Market & Competition)

### Bối cảnh thị trường

**Quy mô:** chưa nghiên cứu. Có thể chạy `bmad-research` nếu cần số liệu.

**Xu hướng:**
- Thanh toán QR ngân hàng phổ biến, nhận tiền gần như tức thì.
- Concert và sự kiện trực tiếp tăng mạnh sau 2023. **[Giả định]**

**Phân khúc mục tiêu:** nhà tổ chức nhỏ và vừa, sự kiện từ vài trăm tới vài nghìn ghế.

### Cạnh tranh

#### Ticketbox
- Điểm mạnh: thương hiệu lớn, nhiều người mua sẵn có.
- Điểm yếu: **[Giả định]** phí cao, phải duyệt sự kiện, nhà tổ chức nhỏ khó vào.
- Vị thế: sàn lớn. NexaTicket chỉ tham khảo cách bố trí giao diện (`architecture-v2/ui-direction.md`).

#### Google Form kèm chuyển khoản
- Điểm mạnh: miễn phí, ai cũng dùng được.
- Điểm yếu: không có sơ đồ ghế, đối soát tay, vé dễ giả.
- Vị thế: đối thủ thật sự ở phân khúc nhỏ.

#### Các nền tảng bán vé khác
- Chưa khảo sát. Cần `bmad-research`.

### Khác biệt của NexaTicket

**Lợi thế:**
- Chọn ghế realtime, chống bán trùng ở mức kỹ thuật đã có test chứng minh.
- Tiền về là có vé, không cần đối soát tay.

**Khoảng trống cần lấp:**
- Chưa có định vị giá, chưa có nhà tổ chức thật nào xác nhận nhu cầu.

---

## 8. Rủi ro và giả định (Risks & Assumptions)

### Rủi ro ưu tiên cao

**Rủi ro 1: Mô hình giữ tiền không hợp pháp**
- Xác suất: cao, nếu ra mắt bằng mô hình escrow v2.
- Tác động: rất cao. Có thể bị xử phạt hoặc phải dừng dịch vụ.
- Giảm thiểu:
  - Giai đoạn đầu để tiền về thẳng tài khoản payOS của từng nhà tổ chức. Nền tảng chỉ thu phí dịch vụ qua hóa đơn.
  - Hoặc hợp tác với một trung gian thanh toán có phép.
  - Hỏi ý kiến luật sư trước khi bán vé thật.
- Người chịu trách nhiệm: chủ dự án.

**Rủi ro 2: Lỗi luồng tiền chưa được kiểm thử đầu-cuối**
- Xác suất: trung bình.
- Tác động: cao (vé không tiền, tiền không vé).
- Giảm thiểu:
  - Viết E2E happy path và các ca webhook trùng/trễ. Hiện `e2e.yml` chỉ là `echo`.
  - Thêm test cho `starter-outbox` và `starter-idempotency` (đang 0 test).
  - Chạy job đối soát hằng ngày.
- Người chịu trách nhiệm: dev backend.

**Rủi ro 3: Vận hành quá tải với 2 người**
- Xác suất: cao.
- Tác động: cao. Sập vào lúc mở bán là mất sự kiện.
- Giảm thiểu:
  - Có cảnh báo, sao lưu DB và thử khôi phục.
  - Có runbook và diễn tập ngày sự kiện.
  - Giới hạn quy mô sự kiện thí điểm.
- Người chịu trách nhiệm: cả nhóm.

**Rủi ro 4: Repo và tài liệu lệch thực tế**
- Xác suất: đã xảy ra.
- Tác động: trung bình. Dễ sửa nhầm chỗ, mất thời gian.
- Biểu hiện:
  - Bản sao `services/` và `platform/` ở thư mục gốc đã cũ.
  - 1.321 file build trong git.
  - README ghi "G0, chưa làm".
  - Docs vẫn ghi SePay hoặc monolith.
- Giảm thiểu: dọn trong tuần 1.

### Giả định cần kiểm chứng
- Nhà tổ chức nhỏ sẵn sàng chuyển từ Ticketbox hoặc Google Form sang NexaTicket.
- Khách chấp nhận trả bằng chuyển khoản QR, không cần thẻ hay ví.
- Một EC2 đủ cho sự kiện thí điểm khoảng 1.000 người cùng lúc.

**Kế hoạch kiểm chứng:** phỏng vấn 3–5 nhà tổ chức trong tuần 1, chạy load test k6 ở mức thí điểm trong tuần 2, bán thử một sự kiện nhỏ trong tuần 4.

---

## 9. Phụ thuộc (Dependencies)

### Phụ thuộc nội bộ
- Keycloak realm production (đã sửa client mobile ở commit `0cabd20`).
- Khóa ký vé đầu tiên. `frontend/docs/chay-local.md` §7 ghi lỗi `NO_SIGNING_KEY`, cần kiểm tra lại.
- Route `/v1/admin/**` và analytics trên gateway, cho màn doanh thu. Cũng nằm trong §7 ở trên, cần kiểm tra lại.

### Phụ thuộc bên ngoài
- payOS: tài khoản production, URL webhook, hạn mức.
- SMTP gửi email, AWS EC2, tên miền `concertth.site` và chứng chỉ TLS.
- Ý kiến pháp lý về mô hình giữ tiền.

### Đang chặn
- Mô hình tiền chưa chốt. Cách gỡ: quyết trong tuần 1, nghiêng về tiền đi thẳng cho nhà tổ chức.
- Chưa có nhà tổ chức thí điểm. Cách gỡ: tìm 1–2 nhà tổ chức quen trong tuần 1.

---

## 10. Bước tiếp theo (Next Steps)

### Việc làm ngay
1. Chốt mô hình tiền và mô hình phí. Ghi vào `bmad-output/decision-log.md`.
2. Dọn repo: xóa bản sao cũ ở thư mục gốc và file build, cập nhật README, đánh dấu docs v1 nào đã bị thay thế.
3. Chạy `bmad-document-project` để có ảnh chụp hiện trạng chuẩn. Sau đó chạy `bmad-prd` để ra PRD cho bản ra mắt 4 tuần.

### Bàn giao

**Giao cho:** Product Manager (`bmad-prd`), Track BMad Method.

**Cần có trước khi giao:**
- Bạn xác nhận các mục **[Giả định]** và **[Cần quyết]** trong brief này.
- Quyết định về mô hình tiền.

---

## Phụ lục

### Nguồn tham khảo
- `docs/BRAINSTORM.md`, `docs/00-discovery/*` (srs, rbac-permission-matrix, success-metrics, product-decisions, legal-constraints-vn)
- `docs/DELIVERY-PLAN-3M.md`, `docs/architecture-v2/*` (README, custodial-funds, services, CHANGELOG, ADR-1001…1014)
- Code: `backend/services/*`, `frontend/apps/*`, `deploy/*`, `.github/workflows/*`

### Stakeholders đã hỏi
- uchihathien: chủ dự án. Đã trả lời mục tiêu (thương mại), hạn chót (<1 tháng) và nhân lực (2 người).
- hanhpham204: thành viên nhóm, chưa phỏng vấn.

### Ghi chú thêm
Mâu thuẫn trong tài liệu cần dọn:
- Monolith (v1) và microservices (v2): v2 thắng.
- SePay và payOS: payOS thắng.
- Kế hoạch 13 tuần và 24–30 tuần: chưa lập lại kế hoạch.
- Có hai ADR cùng số 0016.
- Toàn bộ checkbox trong `WEEKLY-BACKLOG.md` chưa được đánh dấu.

---

**Trạng thái tài liệu:** Draft. Chờ xác nhận các mục giả định.
**Cập nhật lần cuối:** 2026-10-22
