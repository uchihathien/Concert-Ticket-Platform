# Catalog state machines

## Event status

```text
DRAFT --> PUBLISHED : publish (materialize session_seats)
PUBLISHED --> UNPUBLISHED : unpublish
UNPUBLISHED --> PUBLISHED : re-publish (no rematerialize sold seats; only fill new if policy allows — MVP: rematerialize only seats not existing)
PUBLISHED --> CANCELLED : cancel (ops; refunds out of band)
DRAFT --> CANCELLED : abandon
```

## Seat map status

```text
DRAFT --> ACTIVE : activate
ACTIVE --> ARCHIVED : when another map activated or venue archived
```

## Session seat lifecycle (sau materialize)

Owned transitions chi tiết ở milestone 03:

```text
AVAILABLE --> HELD --> AVAILABLE     (release/expire hold)
AVAILABLE --> HELD --> RESERVED --> SOLD
RESERVED --> AVAILABLE               (order expire / cancel)
SOLD --> (terminal for inventory; refund may BLOCKED/AVAILABLE per policy)
BLOCKED = admin holdout
```

## Promotion

`active=true` trong `[starts_at, ends_at]` và dưới `max_redemptions`; Orders enforce atomically.
