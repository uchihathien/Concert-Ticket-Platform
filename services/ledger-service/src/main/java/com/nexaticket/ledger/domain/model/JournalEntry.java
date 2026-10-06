// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.domain.model;

import com.nexaticket.kernel.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root của sổ cái: một nghiệp vụ kế toán gồm từ hai định khoản trở lên.
 *
 * <p><b>Bất biến: tổng Nợ phải bằng tổng Có.</b> Bất biến này được ép ở hai nơi, có chủ đích:
 *
 * <ul>
 *   <li>Ở đây — để báo lỗi sớm, rõ ràng, ngay khi lập bút toán.
 *   <li>Ở database bằng constraint trigger — để một bug trong application cũng không thể làm sai sổ
 *       sách. Đây mới là chốt chặn thật.
 * </ul>
 *
 * <p>Bút toán sau khi lập là bất biến. Sửa sai bằng {@link #reverse}.
 */
public final class JournalEntry {

    private final UUID id;
    private final String entryType;
    private final Instant occurredAt;
    private final SourceRef source;
    private final UUID organizationId;
    private final String idempotencyKey;
    private final String memo;
    private final UUID reversesEntryId;
    private final String createdBy;
    private final List<Posting> postings;

    private JournalEntry(Builder builder) {
        this.id = builder.id;
        this.entryType = builder.entryType;
        this.occurredAt = builder.occurredAt;
        this.source = builder.source;
        this.organizationId = builder.organizationId;
        this.idempotencyKey = builder.idempotencyKey;
        this.memo = builder.memo;
        this.reversesEntryId = builder.reversesEntryId;
        this.createdBy = builder.createdBy;
        this.postings = List.copyOf(builder.postings);
        validate();
    }

    private void validate() {
        Objects.requireNonNull(entryType, "entryType không được null");
        Objects.requireNonNull(occurredAt, "occurredAt không được null");
        Objects.requireNonNull(source, "source không được null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey không được null");
        Objects.requireNonNull(createdBy, "createdBy không được null");

        if (postings.size() < 2) {
            throw new UnbalancedEntryException("Bút toán cần ít nhất 2 định khoản, có " + postings.size());
        }

        Money debit = Money.ZERO;
        Money credit = Money.ZERO;
        for (Posting posting : postings) {
            if (posting.isDebit()) {
                debit = debit.plus(posting.amount());
            } else {
                credit = credit.plus(posting.amount());
            }
        }
        if (!debit.equals(credit)) {
            throw new UnbalancedEntryException("Bút toán không cân: Nợ=" + debit.format() + " Có=" + credit.format());
        }
        if (debit.isZero()) {
            throw new UnbalancedEntryException("Bút toán 0 đồng không có ý nghĩa kế toán");
        }
    }

    /**
     * Bút toán đảo — cách duy nhất để sửa một bút toán sai.
     *
     * <p>Không sửa bản ghi cũ: lịch sử phải giữ nguyên để kiểm toán được.
     */
    public JournalEntry reverse(String reason, Instant now, String actor) {
        List<Posting> reversed = new ArrayList<>();
        int lineNo = 1;
        for (Posting posting : postings) {
            reversed.add(posting.reversed(lineNo++));
        }
        return builder()
                .entryType(entryType + "_REVERSAL")
                .occurredAt(now)
                .source(source)
                .organizationId(organizationId)
                .idempotencyKey("reversal:" + id)
                .memo(reason)
                .reversesEntryId(id)
                .createdBy(actor)
                .postings(reversed)
                .build();
    }

    public Money total() {
        Money debit = Money.ZERO;
        for (Posting posting : postings) {
            if (posting.isDebit()) {
                debit = debit.plus(posting.amount());
            }
        }
        return debit;
    }

    public UUID id() {
        return id;
    }

    public String entryType() {
        return entryType;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public SourceRef source() {
        return source;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String memo() {
        return memo;
    }

    public UUID reversesEntryId() {
        return reversesEntryId;
    }

    public String createdBy() {
        return createdBy;
    }

    public List<Posting> postings() {
        return Collections.unmodifiableList(postings);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Chứng từ gốc làm phát sinh bút toán. */
    public record SourceRef(Type type, UUID id) {
        public enum Type {
            ORDER,
            PAYMENT,
            PAYOUT,
            REFUND,
            ADJUSTMENT
        }

        public static SourceRef payment(UUID paymentAttemptId) {
            return new SourceRef(Type.PAYMENT, paymentAttemptId);
        }

        public static SourceRef payout(UUID payoutRequestId) {
            return new SourceRef(Type.PAYOUT, payoutRequestId);
        }
    }

    /** Ném khi bút toán không cân — lỗi lập trình, không phải lỗi người dùng. */
    public static class UnbalancedEntryException extends RuntimeException {
        public UnbalancedEntryException(String message) {
            super(message);
        }
    }

    public static final class Builder {
        private UUID id = UUID.randomUUID();
        private String entryType;
        private Instant occurredAt;
        private SourceRef source;
        private UUID organizationId;
        private String idempotencyKey;
        private String memo;
        private UUID reversesEntryId;
        private String createdBy;
        private final List<Posting> postings = new ArrayList<>();

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder entryType(String value) {
            this.entryType = value;
            return this;
        }

        public Builder occurredAt(Instant value) {
            this.occurredAt = value;
            return this;
        }

        public Builder source(SourceRef value) {
            this.source = value;
            return this;
        }

        public Builder organizationId(UUID value) {
            this.organizationId = value;
            return this;
        }

        public Builder idempotencyKey(String value) {
            this.idempotencyKey = value;
            return this;
        }

        public Builder memo(String value) {
            this.memo = value;
            return this;
        }

        public Builder reversesEntryId(UUID value) {
            this.reversesEntryId = value;
            return this;
        }

        public Builder createdBy(String value) {
            this.createdBy = value;
            return this;
        }

        public Builder debit(UUID accountId, Money amount) {
            postings.add(Posting.debit(accountId, amount, postings.size() + 1));
            return this;
        }

        public Builder credit(UUID accountId, Money amount) {
            postings.add(Posting.credit(accountId, amount, postings.size() + 1));
            return this;
        }

        public Builder postings(List<Posting> value) {
            postings.clear();
            postings.addAll(value);
            return this;
        }

        public JournalEntry build() {
            return new JournalEntry(this);
        }
    }
}
