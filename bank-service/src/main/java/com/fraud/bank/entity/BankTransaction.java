package com.fraud.bank.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BankTransaction — ledger entry for each debit, credit, or refund operation.
 *
 * Primary purpose: DB-level idempotency guard.
 *   The unique constraint on (transaction_id, type) ensures that any given
 *   payment operation (DEBIT, CREDIT, REFUND) is applied exactly once per
 *   transactionId, even if Kafka redelivers the message multiple times.
 *
 * Secondary purpose: audit trail.
 *   Every balance change is recorded here with the before/after state.
 *   This table is the append-only ledger; BankAccount.balance is the derived view.
 *
 * ─── Idempotency Pattern ─────────────────────────────────────────────────────
 *   BankService checks existsByTransactionIdAndType() before executing any
 *   debit/credit/refund. If a record already exists, it returns 200 without
 *   touching the balance — no double-debit, no double-credit.
 *
 *   The unique constraint is the safety net: even if two threads race through
 *   the existsByTransactionIdAndType() check simultaneously, only one INSERT
 *   will succeed; the other will throw DataIntegrityViolationException which
 *   BankService can catch and treat as an idempotent duplicate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "bank_transactions",
    uniqueConstraints = {
        @UniqueConstraint(
            columnNames = {"transaction_id", "type"},
            name = "uk_bank_tx_id_type"
        )
    }
)
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The payment transaction ID from the upstream event (UUID).
     * Combined with `type` forms the idempotency key.
     */
    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    /**
     * Whether this entry is a DEBIT, CREDIT, or REFUND.
     * Stored as a string for readability in the DB.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionType type;

    /** Email of the account owner affected by this operation */
    @Column(name = "user_id", nullable = false)
    private String userId;

    /** Amount applied in this operation (always positive) */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** Account balance immediately after this operation */
    @Column(name = "balance_after", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    /** Timestamp when this ledger entry was recorded */
    @Column(name = "applied_at", nullable = false, updatable = false)
    private LocalDateTime appliedAt;

    @PrePersist
    public void prePersist() {
        if (this.appliedAt == null) {
            this.appliedAt = LocalDateTime.now();
        }
    }
}
