"""Gọi API NexaTicket bằng tài khoản ban tổ chức dev — dùng chung cho normalize.py và import_events.py.

Cách lấy token giống scripts/vba-arena.py: grant `password` của client `web-admin` (client mật, có
secret dev). Mọi giá trị đọc từ biến môi trường, có mặc định cho máy phát triển.
"""

from __future__ import annotations

import os
import time
import uuid
import warnings

# Python hệ thống của macOS dùng LibreSSL; urllib3 cảnh báo nhưng vẫn chạy đúng với gateway local.
warnings.filterwarnings("ignore", message="urllib3 v2 only supports OpenSSL")

import requests  # noqa: E402

GATEWAY = os.environ.get("GATEWAY_URL", "http://localhost:8080").rstrip("/")
KEYCLOAK = os.environ.get("KEYCLOAK_URL", "http://localhost:8081").rstrip("/")
REALM = os.environ.get("KEYCLOAK_REALM", "nexaticket")
CLIENT_ID = os.environ.get("NEXA_CLIENT_ID", "web-admin")
CLIENT_SECRET = os.environ.get("NEXA_CLIENT_SECRET", "dev-secret-admin")
USERNAME = os.environ.get("NEXA_USERNAME", "organizer")
PASSWORD = os.environ.get("NEXA_PASSWORD", "organizer")
ORG_ID = os.environ.get("ORG_ID", "00000000-0000-4000-a000-000000000001")

# Gateway giới hạn 50 request/giây mỗi người dùng (RATE_LIMIT_RPS). Script chạy sát máy chủ nên
# nhanh hơn thế rất nhiều nếu để tự do — giãn nhịp còn ~20/giây, và gặp 429 thì chờ rồi thử lại.
MIN_INTERVAL_S = 0.05
MAX_RETRIES = 6


class ApiError(RuntimeError):
    def __init__(self, method: str, path: str, status: int, body: str):
        super().__init__(f"{method} {path} → {status}: {body[:300]}")
        self.status = status
        self.body = body


class NexaApi:
    def __init__(self) -> None:
        self.session = requests.Session()
        self.token: str | None = None
        self._last_call = 0.0

    def login(self) -> None:
        response = self.session.post(
            f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token",
            data={
                "grant_type": "password",
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
                "username": USERNAME,
                "password": PASSWORD,
            },
            timeout=30,
        )
        if not response.ok:
            raise ApiError("POST", "/token", response.status_code, response.text)
        self.token = response.json()["access_token"]

    def call(self, method: str, path: str, body=None, idempotent: bool = False):
        if self.token is None:
            self.login()
        headers = {"Authorization": f"Bearer {self.token}"}
        if idempotent:
            # CÙNG một khoá cho mọi lần thử lại của một lệnh: server nhận lệnh rồi mà phản hồi lạc mất
            # thì lần thử lại trả đúng kết quả cũ, không tạo bản thứ hai.
            headers["Idempotency-Key"] = str(uuid.uuid4())
        for attempt in range(MAX_RETRIES + 1):
            wait = MIN_INTERVAL_S - (time.monotonic() - self._last_call)
            if wait > 0:
                time.sleep(wait)
            self._last_call = time.monotonic()
            response = self.session.request(method, GATEWAY + path, json=body, headers=headers, timeout=180)
            if response.status_code == 401 and attempt == 0:
                # Token sống vài phút; lần nhập dài thì xin lại một lần.
                self.login()
                headers["Authorization"] = f"Bearer {self.token}"
                continue
            if response.status_code == 429 and attempt < MAX_RETRIES:
                retry_after = response.headers.get("Retry-After")
                time.sleep(float(retry_after) if retry_after and retry_after.isdigit() else min(8.0, 0.5 * 2**attempt))
                continue
            break
        if not response.ok:
            raise ApiError(method, path, response.status_code, response.text)
        return response.json() if response.content else None

    def org(self, suffix: str) -> str:
        return f"/v1/organizations/{ORG_ID}{suffix}"

    def list_venues(self) -> list[dict]:
        return self.call("GET", self.org("/venues"))
