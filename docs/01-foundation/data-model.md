# Data model — transactional core (MVP)

PostgreSQL là source of record. Mỗi module sở hữu bảng của mình; không FK cross-module cứng buộc nếu cản trở tách service — dùng logical ID + application integrity. MVP cho phép FK trong cùng database để an toàn.

## Enums

```text
membership_role: CUSTOMER | CHECKIN_STAFF | EVENT_MANAGER | ORG_ADMIN | ORG_OWNER | PLATFORM_ADMIN
event_status: DRAFT | PUBLISHED | UNPUBLISHED | CANCELLED
seat_status: AVAILABLE | HELD | RESERVED | SOLD | BLOCKED
hold_status: ACTIVE | RELEASED | EXPIRED | CONVERTED
order_status: AWAITING_PAYMENT | PAID | EXPIRED | CANCELLED | MANUAL_REVIEW | REFUNDED
payment_attempt_status: PENDING | CONFIRMED | REJECTED | DUPLICATE | MANUAL_REVIEW | REFUNDED
ticket_status: VALID | CHECKED_IN | CANCELLED | REFUNDED
promotion_type: PERCENT | FIXED_AMOUNT
```

## Tables (logical DDL)

### Identity

```sql
organizations (
  id UUID PK,
  name TEXT NOT NULL,
  slug TEXT UNIQUE NOT NULL,
  status TEXT NOT NULL, -- ACTIVE | SUSPENDED
  price_tax_mode TEXT NOT NULL DEFAULT 'UNKNOWN',
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

users (
  id UUID PK,
  idp_subject TEXT UNIQUE NOT NULL,
  email TEXT UNIQUE NOT NULL,
  full_name TEXT,
  phone TEXT,
  analytics_subject_id UUID UNIQUE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

organization_members (
  id UUID PK,
  organization_id UUID NOT NULL REFERENCES organizations(id),
  user_id UUID NOT NULL REFERENCES users(id),
  role TEXT NOT NULL,
  UNIQUE (organization_id, user_id)
);
```

### Catalog

```sql
venues (
  id UUID PK,
  organization_id UUID NOT NULL,
  name TEXT NOT NULL,
  city TEXT,
  address TEXT,
  created_at, updated_at
);

venue_seat_maps (
  id UUID PK,
  venue_id UUID NOT NULL,
  version INT NOT NULL,
  name TEXT NOT NULL,
  status TEXT NOT NULL, -- DRAFT | ACTIVE | ARCHIVED
  UNIQUE (venue_id, version)
);

seat_map_seats (
  id UUID PK,
  seat_map_id UUID NOT NULL,
  section TEXT,
  row_label TEXT,
  seat_label TEXT,
  external_code TEXT,
  UNIQUE (seat_map_id, section, row_label, seat_label)
);

events (
  id UUID PK,
  organization_id UUID NOT NULL,
  venue_id UUID NOT NULL,
  seat_map_id UUID NOT NULL,
  slug TEXT UNIQUE NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  city TEXT,
  status TEXT NOT NULL, -- event_status
  published_at TIMESTAMPTZ,
  created_at, updated_at
);

event_sessions (
  id UUID PK,
  event_id UUID NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL,
  ends_at TIMESTAMPTZ,
  sales_opens_at TIMESTAMPTZ,
  sales_closes_at TIMESTAMPTZ,
  status TEXT NOT NULL
);

ticket_tiers (
  id UUID PK,
  event_session_id UUID NOT NULL,
  name TEXT NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'VND',
  unit_price_cents BIGINT NOT NULL, -- VND integer
  capacity_hint INT
);

session_seats (
  id UUID PK,
  event_session_id UUID NOT NULL,
  seat_map_seat_id UUID NOT NULL,
  ticket_tier_id UUID,
  status TEXT NOT NULL, -- seat_status
  price_cents_snapshot BIGINT, -- copied at session materialize
  availability_version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (event_session_id, seat_map_seat_id)
);

promotions (
  id UUID PK,
  organization_id UUID NOT NULL,
  code TEXT,
  type TEXT NOT NULL,
  value_cents BIGINT, -- FIXED
  percent_bps INT,    -- PERCENT, basis points
  starts_at, ends_at,
  max_redemptions INT,
  active BOOLEAN NOT NULL DEFAULT TRUE
);
```

**Materialize rule:** khi publish event/session, copy seats từ seat map → `session_seats` với `AVAILABLE` và price từ tier mapping.

### Inventory

```sql
seat_holds (
  id UUID PK,
  organization_id UUID NOT NULL,
  event_session_id UUID NOT NULL,
  user_id UUID NOT NULL,
  status TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  idempotency_key TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (user_id, idempotency_key)
);

seat_hold_items (
  hold_id UUID NOT NULL,
  session_seat_id UUID NOT NULL,
  PRIMARY KEY (hold_id, session_seat_id)
);
```

Redis key: `hold:{eventSessionId}:{sessionSeatId}` → `{holdId, userId}` TTL 300s.

### Orders & payments

```sql
orders (
  id UUID PK,
  organization_id UUID NOT NULL,
  event_session_id UUID NOT NULL,
  user_id UUID NOT NULL,
  status TEXT NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'VND',
  subtotal_cents BIGINT NOT NULL,
  discount_cents BIGINT NOT NULL DEFAULT 0,
  total_cents BIGINT NOT NULL,
  promotion_id UUID,
  payment_reference TEXT UNIQUE NOT NULL,
  payment_expires_at TIMESTAMPTZ NOT NULL,
  bank_account_id UUID NOT NULL,
  bank_snapshot JSONB NOT NULL, -- code, masked account, name
  idempotency_key TEXT NOT NULL,
  created_at, updated_at,
  UNIQUE (user_id, idempotency_key)
);

order_items (
  id UUID PK,
  order_id UUID NOT NULL,
  session_seat_id UUID NOT NULL UNIQUE,
  ticket_tier_id UUID,
  unit_price_cents BIGINT NOT NULL,
  discount_cents BIGINT NOT NULL DEFAULT 0,
  seat_label_snapshot TEXT NOT NULL
);

bank_accounts (
  id UUID PK,
  organization_id UUID NOT NULL,
  bank_code TEXT NOT NULL,
  account_number_encrypted BYTEA NOT NULL,
  account_number_last4 CHAR(4) NOT NULL,
  account_name TEXT NOT NULL,
  active BOOLEAN NOT NULL,
  effective_from TIMESTAMPTZ,
  effective_to TIMESTAMPTZ,
  created_at, updated_at
);

payment_attempts (
  id UUID PK,
  order_id UUID NOT NULL,
  status TEXT NOT NULL,
  expected_amount_cents BIGINT NOT NULL,
  received_amount_cents BIGINT,
  payment_reference TEXT NOT NULL,
  sepay_transaction_id TEXT UNIQUE,
  payload_hash TEXT,
  reason_code TEXT,
  received_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL
);
```

### Tickets

```sql
tickets (
  id UUID PK,
  order_item_id UUID NOT NULL UNIQUE,
  organization_id UUID NOT NULL,
  event_session_id UUID NOT NULL,
  user_id UUID NOT NULL,
  status TEXT NOT NULL,
  qr_jti UUID UNIQUE NOT NULL,
  qr_expires_at TIMESTAMPTZ,
  issued_at TIMESTAMPTZ NOT NULL
);

check_ins (
  id UUID PK,
  ticket_id UUID NOT NULL UNIQUE, -- one successful check-in
  scanned_by_user_id UUID NOT NULL,
  scanned_at TIMESTAMPTZ NOT NULL,
  result TEXT NOT NULL -- CHECKED_IN only for inserted rows
);
```

### Platform

```sql
outbox (
  id UUID PK,
  aggregate_type TEXT NOT NULL,
  aggregate_id UUID NOT NULL,
  event_type TEXT NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ
);

idempotency_records (
  id UUID PK,
  user_id UUID,
  key TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  response_status INT,
  response_body JSONB,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (user_id, key)
);

audit_logs (
  id UUID PK,
  actor_user_id UUID,
  organization_id UUID,
  action TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id UUID,
  before JSONB,
  after JSONB,
  correlation_id TEXT,
  created_at TIMESTAMPTZ NOT NULL
);
```

## Indexes quan trọng

- `session_seats (event_session_id, status)`
- `seat_holds (expires_at) WHERE status = 'ACTIVE'`
- `orders (payment_expires_at) WHERE status = 'AWAITING_PAYMENT'`
- `orders (payment_reference)`
- `payment_attempts (sepay_transaction_id)`
- `events (status, city, published_at)`

## Ownership map

| Module | Tables |
| --- | --- |
| Identity | organizations, users, organization_members |
| Catalog | venues, venue_seat_maps, seat_map_seats, events, event_sessions, ticket_tiers, session_seats*, promotions |
| Inventory | seat_holds, seat_hold_items (+ updates session_seats status) |
| Orders | orders, order_items |
| Payments | bank_accounts, payment_attempts |
| Tickets | tickets, check_ins |
| Shared infra | outbox, idempotency_records, audit_logs |

\* `session_seats` tạo bởi Catalog lúc materialize; Inventory/Orders/Tickets cập nhật `status` qua service API nội bộ có lock.
