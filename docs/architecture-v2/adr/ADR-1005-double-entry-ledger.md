# ADR-1005: Sổ cái kép, append-only

**Status:** Accepted

## Context

Khi nền tảng giữ tiền, phải trả lời chính xác ở mọi thời điểm: đang giữ bao nhiêu, nợ từng tổ chức bao nhiêu, bao nhiêu đã hứa trả, và số dư ngân hàng có khớp không.

Cách làm phổ biến nhưng sai là một cột `balance` trên bảng tổ chức, cập nhật bằng `UPDATE`. Nó không lưu lịch sử, không giải thích được vì sao ra con số đó, hỏng khi có đồng thời, và không đối soát được với ngân hàng.

## Decision

Sổ cái kép gồm `ledger_accounts`, `journal_entries`, `postings`. Mọi bút toán có tổng Nợ bằng tổng Có, ép bằng `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED` ở PostgreSQL.

`postings` là append-only ở cấp quyền database: `REVOKE UPDATE, DELETE`. Sửa sai chỉ bằng bút toán đảo có tham chiếu tới bút toán gốc.

Tách công nợ tổ chức thành ba tài khoản: đang giữ (`2011`), khả dụng (`2012`), dự phòng hoàn tiền (`2013`). Nhờ đó "số dư khả dụng" là số dư của một tài khoản cụ thể, không phải công thức rải trong code.

Tiền nhận được nhưng chưa xác định chủ ghi vào tài khoản treo (`2030`), không để ngoài sổ.

Số dư tính bằng snapshot định kỳ cộng các định khoản phát sinh sau snapshot, tránh hoàn toàn việc cập nhật một hàng số dư nóng.

## Consequences

Ghi một nghiệp vụ tốn nhiều dòng hơn; đội cần hiểu Nợ/Có. Đổi lại: mọi con số giải thích được, đối soát được với ngân hàng, và kiểm toán được. Với hệ thống giữ tiền thật, đây không phải lựa chọn mà là yêu cầu tối thiểu.

## Validation

Tám bất biến ở [custodial-funds.md §5](../custodial-funds.md#5-bất-biến--kiểm-tra-tự-động-có-alert) chạy tự động có cảnh báo. Quan trọng nhất là bất biến số 6: tổng nghĩa vụ phải trả không được vượt tiền thực có.
