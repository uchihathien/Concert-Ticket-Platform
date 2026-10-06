"""Chèn `location /v1/` vào bốn vhost web TLS của file nginx ĐANG CHẠY trên máy chủ.

    sudo python3 deploy/scripts/nginx-add-v1-location.py
    sudo nginx -t && sudo systemctl reload nginx

VÌ SAO KHÔNG CHÉP `deploy/nginx/nexaticket.conf` ĐÈ LÊN. certbot ghi khối TLS THẲNG vào
/etc/nginx/sites-enabled/nexaticket (listen 443, ssl_certificate, chuyển hướng 80→443). Chép file
của repo lên đó là xoá sạch cấu hình chứng chỉ — cả bảy tên miền chết cùng lúc, và lỗi hiện ra là
"SSL handshake failed" chứ không phải "ai đó vừa chép đè một file". Repo vẫn giữ bản có
`location /v1/` cho MÁY MỚI (server-setup.sh chép trước khi certbot chạy); script này là đường
cho máy ĐANG chạy.

VÌ SAO CẦN `location /v1/`. Bundle của bốn app Next gọi API bằng đường TƯƠNG ĐỐI vì
NEXT_PUBLIC_API_BASE_URL rỗng lúc build ảnh (`??` không bắt chuỗi rỗng). Đo bằng Chrome thật:
`fetch('/v1/sessions/{id}/seats')` -> 404 HTML của Next, rồi bị đẩy sang /login?returnUrl=/v1/...;
cùng URL trên api.concertth.site -> 200 với 56KB JSON. Proxy cùng gốc biến đường tương đối thành
đúng đích, và nên giữ kể cả sau khi biến build được sửa: cùng gốc thì không preflight CORS.

KHÔNG DÙNG REGEX. Script này đi qua JSON của SSM rồi qua heredoc của shell; mỗi dấu `\` là một lớp
escape nữa, và bản trước đã chết im lặng vì `\s` thành một backslash literal — khớp 0 block, in ra
"không chèn gì", trong khi mọi thứ khác báo thành công. Đếm ngoặc bằng tay thì không có lớp nào.

IDEMPOTENT: block đã có `location /v1/` thì bỏ qua. Tự sao lưu trước khi ghi.
"""
import sys, time, shutil

P = '/etc/nginx/sites-enabled/nexaticket'
HOSTS = ['concertth.site', 'to-chuc.concertth.site', 'soat-ve.concertth.site', 'quan-tri.concertth.site']
SNIP = """
    # API cung goc: bundle Next goi /v1/... bang duong tuong doi (NEXT_PUBLIC_API_BASE_URL rong luc build).
    location /v1/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;
        proxy_read_timeout 300s;
    }
"""

src = open(P, encoding='utf-8').read()

def close_brace(t, open_at):
    """Vi tri cua } dong voi { tai open_at."""
    d = 0
    for j in range(open_at, len(t)):
        if t[j] == '{':
            d += 1
        elif t[j] == '}':
            d -= 1
            if d == 0:
                return j
    return -1

def server_blocks(t):
    out, i = [], 0
    while True:
        i = t.find('server', i)
        if i < 0:
            break
        # phai la tu 'server' dung rieng, roi toi '{'
        before = t[i - 1] if i else '\n'
        k = i + len('server')
        while k < len(t) and t[k] in ' \t\r\n':
            k += 1
        if before in '\n\t ;}' and k < len(t) and t[k] == '{':
            e = close_brace(t, k)
            if e > 0:
                out.append((i, e + 1))
                i = e + 1
                continue
        i += len('server')
    return out

blocks = server_blocks(src)
print('  tim thay %d server block' % len(blocks))
for a, b in blocks:
    blk = src[a:b]
    name = next((h for h in HOSTS + ['api.concertth.site', 'tai-khoan.concertth.site', 'media.concertth.site']
                 if 'server_name ' + h + ';' in blk), '?')
    print('    %-26s tls=%-3s co_location_root=%-3s co_v1=%s'
          % (name, 'co' if 'ssl_certificate' in blk else '-',
             'co' if 'location / {' in blk else '-',
             'co' if 'location /v1/' in blk else '-'))

changed = []
for a, b in reversed(blocks):
    blk = src[a:b]
    if 'ssl_certificate' not in blk or 'location /v1/' in blk:
        continue
    name = next((h for h in HOSTS if 'server_name ' + h + ';' in blk), None)
    if not name:
        continue
    k = blk.find('location / {')
    if k < 0:
        continue
    end = close_brace(blk, blk.find('{', k))
    if end < 0:
        continue
    src = src[:a] + blk[:end + 1] + '\n' + SNIP + blk[end + 1:] + src[b:]
    changed.append(name)

if changed:
    shutil.copy(P, P + '.bak.' + str(int(time.time())))
    open(P, 'w', encoding='utf-8').write(src)
    print('  DA CHEN /v1/ vao: ' + ', '.join(reversed(changed)))
else:
    print('  khong chen gi (da co san, hoac khong block nao vua dieu kien)')
