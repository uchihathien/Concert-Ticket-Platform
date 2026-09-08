# ADR-1009: Chỉ dùng RabbitMQ; outbox là nhật ký sự kiện

**Status:** Accepted — **thay thế ADR-1003** (Kafka). Khôi phục và mở rộng ADR-0006.

## Context

ADR-1003 chọn Kafka với hai lý do: sổ cái tài chính cần replay được, và 11 service cần nhiều consumer độc lập trên cùng một sự kiện.

Chủ dự án yêu cầu **chỉ dùng RabbitMQ, không dùng Kafka**. Yêu cầu này hợp lý về mặt vận hành: Kafka cần người biết vận hành nó, và với quy mô đội hiện tại đó là rủi ro thật.

Vấn đề cần giải: RabbitMQ xoá message sau khi ack, nên **không có lịch sử để dựng lại** — đúng thứ mà lập luận của ADR-1003 dựa vào.

## Decision

RabbitMQ là **phương tiện vận chuyển**, không phải nơi lưu trữ. Lịch sử sự kiện nằm ở PostgreSQL.

### 1. Outbox là nhật ký sự kiện, giữ vĩnh viễn

Bảng `outbox` của mỗi service vốn đã là bản ghi bền vững, có thứ tự, chỉ ghi thêm, của mọi integration event. Thay đổi so với v1: **không xoá dòng outbox sau khi publish**, chỉ đánh dấu `published_at`.

```sql
outbox (
  id UUID PRIMARY KEY,
  seq BIGSERIAL UNIQUE NOT NULL,          -- thứ tự phát sinh trong service
  aggregate_type TEXT NOT NULL,
  aggregate_id UUID NOT NULL,
  event_type TEXT NOT NULL,
  event_version INT NOT NULL DEFAULT 1,
  payload JSONB NOT NULL,
  correlation_id TEXT, causation_id TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ                 -- NULL = chưa gửi
);
CREATE INDEX ON outbox (published_at) WHERE published_at IS NULL;
CREATE INDEX ON outbox (aggregate_type, aggregate_id, seq);
```

Replay = đọc lại `outbox` theo `seq` và publish lại. Idempotency phía consumer (`processed_events`) khiến việc này an toàn.

Lưu trữ: topic tài chính giữ vĩnh viễn; các event tần suất cao (`availability.changed`) **không đi qua outbox** mà publish trực tiếp sau commit — chúng là dữ liệu tạm, mất cũng không sao vì client tự refetch khi phát hiện nhảy version.

Phân vùng theo tháng cho `outbox` của service có lượng ghi lớn để bảng không phình.

### 2. Sổ cái không cần replay để tồn tại

Điểm quan trọng ADR-1003 đã đánh giá quá nặng: `journal_entries` và `postings` **là** bản ghi gốc, append-only trong PostgreSQL. Sổ cái không dựng lại từ event stream — nó tự là nguồn chân lý. Kafka chưa bao giờ thực sự cần thiết cho mục đích này.

### 3. Topology RabbitMQ

```
Exchange (topic, durable): nexaticket.<context>
  ví dụ: nexaticket.payment, nexaticket.ordering

Routing key: <aggregate>.<event>
  ví dụ: payment.confirmed, order.paid

Queue (quorum, durable): <consumer>.<source>.<event>
  ledger.payment.confirmed      ← nexaticket.payment / payment.confirmed
  ordering.payment.confirmed    ← nexaticket.payment / payment.confirmed
  ticketing.ordering.paid       ← nexaticket.ordering / order.paid
  inventory.ordering.paid       ← nexaticket.ordering / order.paid

Dead letter: mọi queue có x-dead-letter-exchange = nexaticket.dlx → <queue>.dlq
Retry:       nexaticket.retry (x-delayed-message plugin), backoff 5s/30s/2m/10m/1h, tối đa 5 lần
```

**Quorum queue**, không dùng classic mirrored queue (đã lỗi thời). **Publisher confirms** bắt buộc: chỉ đánh dấu `outbox.published_at` sau khi broker xác nhận.

Nhiều consumer trên cùng một sự kiện được giải quyết bằng nhiều queue cùng bind vào một exchange — đây là điều RabbitMQ làm tốt, và là lý do lập luận thứ hai của ADR-1003 không đứng vững.

### 4. Thứ tự message — chỗ RabbitMQ thật sự yếu

Kafka bảo đảm thứ tự trong một partition. RabbitMQ không có khái niệm tương đương: nhiều consumer trên một queue có thể xử lý message của cùng một đơn hàng không đúng thứ tự.

Ba biện pháp, theo thứ tự ưu tiên:

1. **Consumer không phụ thuộc thứ tự** (nguyên tắc chính). Mọi handler dùng điều kiện trạng thái thay vì giả định thứ tự: `UPDATE orders SET status='PAID' WHERE id=? AND status='AWAITING_PAYMENT'`. Message đến sớm/muộn đều cho kết quả đúng.
2. **Concurrency = 1** cho consumer nhạy thứ tự — cụ thể là `ledger.*`. Sổ cái có lưu lượng thấp (theo tốc độ chuyển khoản ngân hàng, không phải tốc độ bấm nút), nên một consumer là quá đủ và đổi lại được tính tất định.
3. **Consistent-hash exchange plugin** nếu sau này cần vừa song song vừa giữ thứ tự theo khoá — băm theo `aggregate_id` ra N queue, mỗi queue một consumer. Đây là tương đương gần nhất của partition key. Chưa dùng ở MVP.

### 5. Fan-out cho realtime-gateway

```
Exchange nexaticket.availability (fanout, durable)
Queue rt-gw.<instanceId>: exclusive, auto-delete,
      x-message-ttl = 30s, x-max-length = 10000, overflow = drop-head
```

Mỗi instance một queue tạm, tự xoá khi instance chết. TTL và giới hạn độ dài là cố ý: dữ liệu khả dụng ghế cũ vô giá trị, thà bỏ còn hơn dồn ứ — client phát hiện nhảy version sẽ tự refetch.

## Consequences

Tích cực: một hạ tầng ít hơn phải vận hành; đội đã quen RabbitMQ từ v1; chi phí thấp hơn rõ rệt; DevOps giảm từ 1.5–2 xuống ~1.

Tiêu cực và phải chấp nhận:
- Không có replay sẵn có — phải tự viết công cụ replay từ `outbox`. Ước tính 2–3 ngày công.
- Không có bảo đảm thứ tự — bù bằng thiết kế consumer không phụ thuộc thứ tự (kỷ luật code, phải review kỹ).
- Bảng `outbox` phình theo thời gian — cần phân vùng và chính sách lưu trữ.
- Không có schema registry — thay bằng JSON Schema trong `packages/api-contracts/events/` và test hợp đồng trong CI.

## Validation

- Test: giết broker giữa lúc xử lý thanh toán, khôi phục, xác nhận không mất event và không phát hành vé trùng.
- Test: gửi `PaymentConfirmed` và `OrderExpired` **sai thứ tự** cho cùng một đơn; kết quả cuối phải đúng.
- Diễn tập replay: xoá `processed_events` của ledger, replay từ `outbox` của payment-service, xác nhận sổ cái dựng lại khớp từng đồng.
- Kiểm tra: `outbox` không có dòng nào `published_at IS NULL` quá 60 giây (alert).
