"""Gỡ (hoặc đặt) attributes.frontendUrl của realm nexaticket trên Keycloak ĐANG CHẠY qua admin API.

python -X utf8 kc_frontend_url.py            # xem giá trị hiện tại
python -X utf8 kc_frontend_url.py clear      # gỡ
python -X utf8 kc_frontend_url.py set <url>  # đặt lại (vd. tunnel cho mobile)

Chỉ đổi cấu hình runtime; file deploy/keycloak/nexaticket-realm.json giữ nguyên.
Mật khẩu admin là giá trị dev trong deploy/compose/infra.yml (KC_BOOTSTRAP_ADMIN_*).
"""
import json, sys, urllib.parse, urllib.request

KC = "http://localhost:8081"
REALM = "nexaticket"


def admin_token():
    body = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli",
                                   "username": "admin", "password": "admin"}).encode()
    with urllib.request.urlopen(f"{KC}/realms/master/protocol/openid-connect/token", body, timeout=30) as r:
        return json.load(r)["access_token"]


def api(method, path, tok, body=None):
    req = urllib.request.Request(f"{KC}/admin/realms/{REALM}{path}", method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


tok = admin_token()
realm = api("GET", "", tok)
attrs = realm.get("attributes", {})
print("frontendUrl hiện tại:", repr(attrs.get("frontendUrl")))
cmd = sys.argv[1] if len(sys.argv) > 1 else "show"
if cmd == "clear":
    attrs["frontendUrl"] = ""
elif cmd == "set":
    attrs["frontendUrl"] = sys.argv[2]
else:
    sys.exit()
api("PUT", "", tok, {"attributes": attrs})
disc = json.load(urllib.request.urlopen(f"{KC}/realms/{REALM}/.well-known/openid-configuration"))
print("issuer sau khi đổi:", disc["issuer"])
