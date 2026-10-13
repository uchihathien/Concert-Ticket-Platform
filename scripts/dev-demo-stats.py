#!/usr/bin/env python3
"""Dữ liệu demo cho các trang thống kê của ban tổ chức. CHỈ dành cho máy phát triển.

    ./scripts/dev-demo-stats.py seed     # dọn dữ liệu DEMO cũ rồi tạo lại (chạy lại không nhân đôi)
    ./scripts/dev-demo-stats.py clean    # xoá đúng những gì script này đã tạo
    ./scripts/dev-demo-stats.py status   # đếm dữ liệu DEMO đang có

Script làm gì
-------------
Tồn kho dev đã có sẵn hàng chục nghìn chỗ ở trạng thái SOLD (dữ liệu mẫu cũ) nhưng không có đơn
hàng hay vé nào đứng sau chúng. Script dựng phần còn thiếu:

* ordering_db   đơn DEMO đã trả cho đúng những chỗ SOLD ấy, cộng thêm đơn quá hạn và đơn huỷ
* ticketing_db  vé cho mọi dòng vé của đơn đã trả, và nhật ký soát vé (vào, ra, vào lại, bị từ chối)
                cho những suất đã mở cổng — trạng thái vé khớp với nhật ký
* inventory_db  CHỈ ĐỌC. Không một dòng nào ở đây bị sửa.

Nhận diện dữ liệu DEMO
----------------------
Mã đơn bắt đầu bằng ``DEMO-``; mọi khoá chính do script sinh ra bắt đầu bằng ``d0d0d0d0-``; nhật ký
soát vé mang ``device_id = 'DEMO-SEED'``. Lệnh ``clean`` xoá theo đúng ba dấu ấy và không chạm vào
bất cứ thứ gì khác.

Những gì script KHÔNG làm
-------------------------
* Không ghi sổ cái, không ghi bảng outbox — không có event nào ra RabbitMQ, không có thư nào được gửi.
* Không tạo đơn đang chờ thanh toán: worker hết hạn của ordering sẽ nhặt chúng lên và gọi sang
  payment, inventory.
* Không đi qua analytics-service. Khối "master data" của trang Tổng quan đọc analytics nên sẽ KHÔNG
  thấy doanh thu DEMO; các trang thống kê mới đọc ordering nên thấy.

An toàn
-------
Script chỉ nói chuyện với Postgres qua ``docker exec`` vào container của ``deploy/compose/infra.yml``
và dùng tài khoản có sẵn trong chính container đó. Nó không đọc, không nhận và không in ra chuỗi
kết nối hay mật khẩu nào, và từ chối chạy nếu container không thuộc compose project dev.
"""

import csv
import hashlib
import io
import os
import random
import subprocess
import sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone

CONTAINER = os.environ.get("POSTGRES_CONTAINER", "nexaticket-infra-postgres-1")
DEV_COMPOSE_PROJECT = "nexaticket-infra"
ID_PREFIX = "d0d0d0d0"
ORDER_PREFIX = "DEMO-"
DEVICE = "DEMO-SEED"

VN = timezone(timedelta(hours=7))
COMMISSION_BPS = 500
PAYMENT_WINDOW = timedelta(minutes=15)
GATE_OPENS_BEFORE = timedelta(hours=2)
SALES_LOOKBACK_DAYS = 30

# Cùng một hạt giống cho mọi lần chạy: hai lần `seed` liên tiếp cho ra cùng một bộ dữ liệu (chỉ lệch
# theo "bây giờ"), nên ảnh chụp màn hình trong báo cáo lặp lại được.
RNG = random.Random(20261013)

# Trọng số theo giờ trong ngày (giờ Việt Nam): giờ nghỉ trưa và buổi tối là lúc người ta mua vé.
HOUR_WEIGHTS = [1, 1, 0.5, 0.3, 0.3, 0.5, 1, 2, 4, 6, 7, 8, 11, 9, 6, 6, 6, 7, 9, 12, 15, 14, 9, 4]
ORDER_SIZES = [1, 2, 3, 4, 5, 6]
ORDER_SIZE_WEIGHTS = [28, 40, 13, 13, 4, 2]

FAMILY = ["Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Huỳnh", "Phan", "Vũ", "Võ", "Đặng", "Bùi", "Đỗ", "Hồ", "Ngô"]
MIDDLE = ["Văn", "Thị", "Minh", "Ngọc", "Thanh", "Hoàng", "Quốc", "Thu", "Đức", "Gia", "Khánh", "Bảo"]
GIVEN = [
    "An", "Bình", "Chi", "Dũng", "Giang", "Hà", "Hải", "Hạnh", "Hiếu", "Hoa", "Hùng", "Hương", "Khoa",
    "Lan", "Linh", "Long", "Mai", "Nam", "Nga", "Nhung", "Phong", "Phúc", "Quân", "Quỳnh", "Sơn", "Tâm",
    "Thảo", "Trang", "Trung", "Tuấn", "Tú", "Vy", "Yến",
]  # fmt: skip
OVERRIDE_REASONS = [
    "Điện thoại hết pin, đã đối chiếu giấy tờ",
    "Mã QR bị mờ, tra theo tên người mua",
    "Khách của ban tổ chức",
]
GATES = ["Cổng A", "Cổng B", "Cổng C"]


def fail(message):
    sys.exit("dev-demo-stats: " + message)


def guard():
    """Từ chối chạy ở bất cứ đâu không phải máy dev."""
    for name in ("SPRING_PROFILES_ACTIVE", "NEXATICKET_ENV", "APP_ENV", "NODE_ENV"):
        if "prod" in os.environ.get(name, "").lower():
            fail("biến môi trường %s đang chỉ prod — script này chỉ dành cho dev." % name)
    try:
        project = subprocess.run(
            ["docker", "inspect", CONTAINER, "--format", '{{index .Config.Labels "com.docker.compose.project"}}'],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        fail("không thấy container %s. Chạy hạ tầng dev trước (deploy/compose/infra.yml)." % CONTAINER)
    if project != DEV_COMPOSE_PROJECT:
        fail("container %s thuộc compose project '%s', không phải '%s'." % (CONTAINER, project, DEV_COMPOSE_PROJECT))
    databases = set(read("postgres", "SELECT datname FROM pg_database", single=True))
    for needed in ("ordering_db", "ticketing_db", "inventory_db", "catalog_db", "identity_db"):
        if needed not in databases:
            fail("thiếu database %s — đây không giống môi trường dev của dự án." % needed)


def psql(database, script):
    # Tài khoản lấy từ chính biến môi trường của container; không có gì về kết nối nằm ở đây.
    command = 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d ' + database
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "sh", "-c", command],
        input=script,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        fail("lỗi ở %s:\n%s" % (database, result.stderr.strip()[-2000:]))
    return result.stdout


def read(database, query, single=False):
    out = psql(database, "COPY (%s) TO STDOUT WITH (FORMAT csv);\n" % query)
    rows = list(csv.reader(io.StringIO(out)))
    return [row[0] for row in rows] if single else rows


def copy_block(table, columns, rows):
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    for row in rows:
        writer.writerow(["" if value is None else value for value in row])
    return "COPY %s (%s) FROM STDIN WITH (FORMAT csv);\n%s\\.\n" % (table, ", ".join(columns), buffer.getvalue())


def demo_id(*parts):
    digest = hashlib.md5(":".join(str(p) for p in parts).encode()).hexdigest()
    return "%s-%s-4%s-8%s-%s" % (ID_PREFIX, digest[0:4], digest[4:7], digest[7:10], digest[10:22])


def ts(value):
    return value.astimezone(timezone.utc).isoformat()


def parse(value):
    """Thời điểm đọc từ database dưới dạng số giây epoch — không phụ thuộc cách psql in timestamptz."""
    return datetime.fromtimestamp(float(value), timezone.utc)


def flag(value):
    return "t" if value else "f"


# --- Dọn ---------------------------------------------------------------------------------------

CLEAN_TICKETING = """
BEGIN;
DELETE FROM checkin_log
 WHERE device_id = '{device}' OR id::text LIKE '{prefix}-%' OR ticket_id::text LIKE '{prefix}-%';
DELETE FROM tickets WHERE id::text LIKE '{prefix}-%';
COMMIT;
""".format(device=DEVICE, prefix=ID_PREFIX)

CLEAN_ORDERING = """
BEGIN;
DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE order_number LIKE '{order}%');
DELETE FROM orders WHERE order_number LIKE '{order}%';
COMMIT;
""".format(order=ORDER_PREFIX)


def clean():
    psql("ticketing_db", CLEAN_TICKETING)
    psql("ordering_db", CLEAN_ORDERING)


def status():
    orders = read(
        "ordering_db",
        "SELECT status, count(*), coalesce(sum(total_vnd), 0) FROM orders "
        "WHERE order_number LIKE '%s%%' GROUP BY 1 ORDER BY 1" % ORDER_PREFIX,
    )
    tickets = read("ticketing_db", "SELECT count(*) FROM tickets WHERE id::text LIKE '%s-%%'" % ID_PREFIX, single=True)
    logs = read("ticketing_db", "SELECT count(*) FROM checkin_log WHERE device_id = '%s'" % DEVICE, single=True)
    print("Dữ liệu DEMO hiện có:")
    for row in orders:
        print("  đơn %-10s %7s đơn  %15s đ" % (row[0], row[1], "{:,}".format(int(row[2])).replace(",", ".")))
    if not orders:
        print("  (không có đơn DEMO nào)")
    print("  vé: %s · dòng nhật ký soát vé: %s" % (tickets[0], logs[0]))


# --- Tạo ---------------------------------------------------------------------------------------


def pick_time(start, end):
    """Một thời điểm đặt đơn trong [start, end]: dồn về cuối kỳ, cuối tuần và giờ cao điểm."""
    if end <= start:
        return start
    days = max(1, (end.astimezone(VN).date() - start.astimezone(VN).date()).days + 1)
    first = start.astimezone(VN).date()
    weights = []
    for offset in range(days):
        day = first + timedelta(days=offset)
        weight = 1.0 + 2.5 * ((offset + 1) / days) ** 2
        if day.weekday() >= 5:
            weight *= 1.35
        weights.append(weight)
    for _ in range(12):
        day = first + timedelta(days=RNG.choices(range(days), weights)[0])
        hour = RNG.choices(range(24), HOUR_WEIGHTS)[0]
        moment = datetime(day.year, day.month, day.day, hour, RNG.randrange(60), RNG.randrange(60), tzinfo=VN)
        if start <= moment <= end:
            return moment
    return start + (end - start) * RNG.random()


def seed():
    now = datetime.now(timezone.utc)
    clean()

    sessions = {}
    for sid, starts, ends in read("catalog_db", "SELECT id, extract(epoch FROM starts_at), extract(epoch FROM ends_at) FROM event_sessions"):
        sessions[sid] = (parse(starts), parse(ends))

    inventory = {}
    for sid, event_id, org_id, opens in read(
        "inventory_db", "SELECT event_session_id, event_id, organization_id, extract(epoch FROM sales_open_at) FROM session_inventory"
    ):
        if sid in sessions:
            inventory[sid] = (event_id, org_id, parse(opens))

    # Chỗ đã có đơn thật đứng sau thì không dựng đơn DEMO đè lên.
    taken = set(
        read(
            "ordering_db",
            "SELECT i.session_seat_id FROM order_items i JOIN orders o ON o.id = i.order_id "
            "WHERE o.status IN ('PAID', 'AWAITING_PAYMENT', 'MANUAL_REVIEW')",
            single=True,
        )
    )

    seat_columns = "id, event_session_id, seat_code, zone_code, admission_type, seat_label, ticket_type_id, ticket_type_name, price_vnd"
    sold = defaultdict(list)
    for row in read("inventory_db", "SELECT %s FROM session_seats WHERE status = 'SOLD' ORDER BY id" % seat_columns):
        if row[0] not in taken and row[1] in inventory:
            sold[row[1]].append(row)
    # Đơn quá hạn và đơn huỷ cần mã chỗ để ghi vào dòng vé, nhưng chỗ ấy đã được nhả: lấy từ những
    # chỗ đang trống. Chỉ đọc mã, không đổi trạng thái của chúng.
    free = defaultdict(list)
    for row in read(
        "inventory_db",
        "SELECT %s FROM (SELECT *, row_number() OVER (PARTITION BY event_session_id ORDER BY id) AS n "
        "FROM session_seats WHERE status = 'AVAILABLE') s WHERE n <= 400" % seat_columns,
    ):
        if row[1] in inventory:
            free[row[1]].append(row)

    scanners = defaultdict(list)
    for org_id, user_id, role in read("identity_db", "SELECT organization_id, user_id, role FROM organization_members"):
        scanners[org_id].append((user_id, role))

    orders, items, tickets, logs = [], [], [], []
    buyers = []
    counters = {"order": 0}

    def buyer():
        # Bảy phần là khách mới, ba phần là khách quay lại — để chỉ số "mới / quay lại" có nghĩa.
        if buyers and RNG.random() < 0.3:
            return RNG.choice(buyers)
        index = len(buyers)
        name = "%s %s %s" % (RNG.choice(FAMILY), RNG.choice(MIDDLE), RNG.choice(GIVEN))
        buyers.append((demo_id("buyer", index), name))
        return buyers[-1]

    def add_order(sid, seats, order_status, created):
        counters["order"] += 1
        number = counters["order"]
        event_id, org_id, _ = inventory[sid]
        order_id = demo_id("order", number)
        user_id, name = buyer()
        total = sum(int(seat[8]) for seat in seats)
        commission = sum(int(seat[8]) * COMMISSION_BPS // 10000 for seat in seats)
        paid_at = created + timedelta(seconds=RNG.randint(35, 640)) if order_status == "PAID" else None
        closed_at, reason = None, None
        if order_status == "EXPIRED":
            closed_at, reason = created + PAYMENT_WINDOW + timedelta(seconds=RNG.randint(1, 50)), "Quá hạn chuyển khoản"
        elif order_status == "CANCELLED":
            closed_at, reason = created + timedelta(seconds=RNG.randint(20, 500)), "Khách huỷ đơn"
        orders.append([
            order_id, "%s%06d" % (ORDER_PREFIX, number), sid, event_id, org_id, user_id, demo_id("hold", number),
            order_status, total, 0, total, COMMISSION_BPS, commission, ts(created + PAYMENT_WINDOW), ts(created),
            ts(paid_at) if paid_at else None, ts(closed_at) if closed_at else None, reason,
        ])  # fmt: skip
        issued = []
        for seat in seats:
            item_id = demo_id("item", number, seat[0])
            price = int(seat[8])
            items.append([
                item_id, order_id, seat[0], seat[2], seat[3], seat[4], seat[5] or None, seat[6], seat[7], price, 0,
                COMMISSION_BPS, price * COMMISSION_BPS // 10000,
            ])  # fmt: skip
            if order_status == "PAID":
                issued.append({
                    "id": demo_id("ticket", item_id), "order_id": order_id, "item_id": item_id, "sid": sid,
                    "org": org_id, "user": user_id, "seat": seat, "name": name, "issued": paid_at + timedelta(seconds=1),
                    "entries": 0, "inside": False, "first_in": None, "first_by": None, "last_in": None,
                    "last_gate": None, "last_out": None,
                })  # fmt: skip
        return issued

    groups_by_session = defaultdict(list)
    for sid, seats in sold.items():
        starts, _ = sessions[sid]
        _, _, opens = inventory[sid]
        window_end = min(starts - timedelta(hours=1), now - timedelta(minutes=20))
        window_start = max(opens, window_end - timedelta(days=SALES_LOOKBACK_DAYS))
        if window_end <= window_start:
            window_start = window_end - timedelta(days=7)
        RNG.shuffle(seats)
        cursor = 0
        while cursor < len(seats):
            size = RNG.choices(ORDER_SIZES, ORDER_SIZE_WEIGHTS)[0]
            chunk = seats[cursor : cursor + size]
            cursor += size
            issued = add_order(sid, chunk, "PAID", pick_time(window_start, window_end))
            groups_by_session[sid].append(issued)
        # Cứ 100 đơn đã trả thì có khoảng 27 đơn quá hạn và 9 đơn huỷ.
        pool = free.get(sid, [])
        paid_count = len(groups_by_session[sid])
        for order_status, ratio in (("EXPIRED", 0.27), ("CANCELLED", 0.09)):
            for _ in range(int(paid_count * ratio)):
                if not pool:
                    break
                chunk = RNG.sample(pool, min(len(pool), RNG.choices(ORDER_SIZES, ORDER_SIZE_WEIGHTS)[0]))
                add_order(sid, chunk, order_status, pick_time(window_start, window_end))

    # --- Soát vé: chỉ những suất đã mở cổng ---
    log_counter = {"n": 0}

    def add_log(ticket, sid, org_id, scanner, result, when, direction="IN", method="QR", note=None):
        log_counter["n"] += 1
        logs.append([
            demo_id("log", log_counter["n"]), ticket["id"] if ticket else None, sid, scanner[0], DEVICE, result, note,
            ts(when), direction, scanner[1], method, org_id,
        ])  # fmt: skip

    for sid, groups in groups_by_session.items():
        starts, ends = sessions[sid]
        gate_opens = starts - GATE_OPENS_BEFORE
        if gate_opens >= now:
            continue
        _, org_id, _ = inventory[sid]
        members = scanners.get(org_id, [])
        staff = [m for m in members if m[1] == "CHECKIN_STAFF"] or members
        if not staff:
            continue
        # Mỗi người đứng một cổng. Ban tổ chức thỉnh thoảng tự quét, nên cũng có mặt trong danh sách.
        crew = [(user_id, GATES[i % len(GATES)]) for i, (user_id, _) in enumerate(staff)]
        owners = [m for m in members if m[1] != "CHECKIN_STAFF"]
        if owners:
            crew.append((owners[0][0], GATES[len(crew) % len(GATES)]))
        crew_weights = [6] * (len(crew) - 1) + [1] if owners and len(crew) > 1 else [1] * len(crew)

        horizon = min(ends, now)
        ended = ends <= now
        arrival_rate = RNG.uniform(0.72, 0.93)
        arrived_tickets = []
        for issued in groups:
            if not issued or RNG.random() > arrival_rate:
                continue
            arrival = starts + timedelta(minutes=RNG.gauss(-40, 28))
            arrival = max(gate_opens + timedelta(minutes=1), arrival)
            if arrival >= horizon:
                continue
            scanner = RNG.choices(crew, crew_weights)[0]
            for offset, ticket in enumerate(issued):
                when = arrival + timedelta(seconds=7 * offset + RNG.randint(0, 4))
                roll = RNG.random()
                method, note = "QR", None
                if roll < 0.012:
                    method, note = "OVERRIDE", RNG.choice(OVERRIDE_REASONS)
                elif roll < 0.05:
                    method = "CODE"
                add_log(ticket, sid, org_id, scanner, "ACCEPTED", when, method=method, note=note)
                ticket.update(entries=1, inside=True, first_in=when, first_by=scanner[0], last_in=when, last_gate=scanner[1])
                arrived_tickets.append(ticket)

                # Ra ngoài rồi (đôi khi) vào lại.
                if RNG.random() < (0.3 if ended else 0.1):
                    out_at = when + timedelta(minutes=RNG.randint(20, 90))
                    if out_at < horizon:
                        gate = RNG.choices(crew, crew_weights)[0]
                        add_log(ticket, sid, org_id, gate, "EXITED", out_at, direction="OUT")
                        ticket.update(inside=False, last_out=out_at)
                        back_at = out_at + timedelta(minutes=RNG.randint(5, 25))
                        if RNG.random() < 0.4 and back_at < horizon:
                            gate = RNG.choices(crew, crew_weights)[0]
                            add_log(ticket, sid, org_id, gate, "ACCEPTED", back_at)
                            ticket.update(entries=2, inside=True, last_in=back_at, last_gate=gate[1])

        # Những lượt quét bị từ chối: khoảng 4% số lượt vào.
        everyone = [ticket for issued in groups for ticket in issued]
        for _ in range(max(2, len(arrived_tickets) // 25)):
            scanner = RNG.choices(crew, crew_weights)[0]
            kind = RNG.choices(
                ["ALREADY_CHECKED_IN", "WRONG_SESSION", "INVALID_TOKEN", "NOT_OPEN_YET", "NOT_INSIDE"], [45, 20, 15, 12, 8]
            )[0]
            if kind == "ALREADY_CHECKED_IN" and arrived_tickets:
                ticket = RNG.choice(arrived_tickets)
                add_log(ticket, sid, org_id, scanner, kind, ticket["first_in"] + timedelta(seconds=RNG.randint(8, 600)))
            elif kind == "NOT_OPEN_YET" and everyone:
                early = gate_opens - timedelta(minutes=RNG.randint(5, 70))
                add_log(RNG.choice(everyone), sid, org_id, scanner, kind, early)
            elif kind == "NOT_INSIDE" and everyone:
                absent = [t for t in everyone if t["entries"] == 0]
                when = starts + timedelta(minutes=RNG.randint(0, 60))
                if absent and when < horizon:
                    add_log(RNG.choice(absent), sid, org_id, scanner, kind, when, direction="OUT")
            else:
                when = starts + timedelta(minutes=RNG.randint(-90, 30))
                if gate_opens < when < horizon:
                    add_log(None, sid, org_id, scanner, kind if kind in ("WRONG_SESSION", "INVALID_TOKEN") else "INVALID_TOKEN", when)

    for issued_groups in groups_by_session.values():
        for issued in issued_groups:
            for t in issued:
                seat = t["seat"]
                arrived = t["entries"] > 0
                tickets.append([
                    t["id"], t["order_id"], t["item_id"], t["sid"], t["org"], t["user"], seat[0], seat[2], seat[3],
                    seat[4], seat[5] or None, seat[7], "CHECKED_IN" if arrived else "VALID", ts(t["issued"]),
                    ts(t["first_in"]) if arrived else None, t["first_by"], t["name"], "PAID", flag(t["inside"]),
                    t["entries"], ts(t["last_in"]) if t["last_in"] else None, t["last_gate"],
                    ts(t["last_out"]) if t["last_out"] else None,
                ])  # fmt: skip

    psql(
        "ordering_db",
        "BEGIN;\n"
        + copy_block(
            "orders",
            ["id", "order_number", "event_session_id", "event_id", "organization_id", "user_id", "hold_id", "status",
             "subtotal_vnd", "discount_vnd", "total_vnd", "commission_bps", "commission_vnd", "payment_expires_at",
             "created_at", "paid_at", "closed_at", "close_reason"],
            orders,
        )  # fmt: skip
        + copy_block(
            "order_items",
            ["id", "order_id", "session_seat_id", "seat_code", "zone_code", "admission_type", "seat_label",
             "ticket_type_id", "ticket_type_name", "unit_price_vnd", "discount_vnd", "commission_bps", "commission_vnd"],
            items,
        )  # fmt: skip
        + "COMMIT;\n",
    )
    psql(
        "ticketing_db",
        "BEGIN;\n"
        + copy_block(
            "tickets",
            ["id", "order_id", "order_item_id", "event_session_id", "organization_id", "user_id", "session_seat_id",
             "seat_code", "zone_code", "admission_type", "seat_label", "ticket_type_name", "status", "issued_at",
             "checked_in_at", "checked_in_by", "holder_name", "payment_status", "inside", "entry_count",
             "last_entry_at", "last_entry_gate", "last_exit_at"],
            tickets,
        )  # fmt: skip
        + copy_block(
            "checkin_log",
            ["id", "ticket_id", "event_session_id", "scanned_by", "device_id", "result", "note", "scanned_at",
             "direction", "gate", "entry_method", "organization_id"],
            logs,
        )  # fmt: skip
        + "COMMIT;\n",
    )
    print("Đã tạo %d đơn, %d dòng vé, %d vé, %d dòng nhật ký soát vé." % (len(orders), len(items), len(tickets), len(logs)))
    status()
    print(
        "\nLưu ý: dữ liệu DEMO không đi qua analytics-service. Khối master data của trang Tổng quan (đọc\n"
        "analytics) sẽ không thấy doanh thu này; các trang thống kê mới (đọc ordering) thì thấy."
    )


def main():
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command not in ("seed", "clean", "status"):
        sys.exit(__doc__.split("\n\n")[1])
    guard()
    if command == "seed":
        seed()
    elif command == "clean":
        clean()
        status()
    else:
        status()


if __name__ == "__main__":
    main()
