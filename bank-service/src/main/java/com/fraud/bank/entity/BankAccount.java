package com.fraud.bank.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * BankAccount — JPA entity representing a user's simulated bank account (Polish v3)
 *
 * This service simulates the NPCI bank backend.
 * Balance is stored in the primary currency unit (e.g. INR).
 * All debit/credit operations go through BankService which enforces
 * pessimistic write locking and idempotency via BankTransaction records.
 *
 * IMPORTANT: This is a SIMULATION — no real money is held here.
 * New accounts are seeded with a default balance of 10,000.
 *
 * Account Number Generation:
 *   Uses UUID-based generation (first 12 hex chars of a UUID) to produce
 *   collision-resistant, unpredictable account numbers. The hash-based
 *   approach was replaced because it was deterministic from userId, making
 *   account numbers predictable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bank_accounts",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = "user_id",       name = "uk_accounts_user_id"),
           @UniqueConstraint(columnNames = "upi_id",        name = "uk_accounts_upi_id"),
           @UniqueConstraint(columnNames = "account_number", name = "uk_accounts_account_no")
       })
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Email of the account owner — matches auth-service User.email.
     * Used as the primary lookup key for debit/credit operations.
     */
    @Column(name = "user_id", nullable = false, unique = true)
    private String userId;

    /**
     * UPI ID linked to this account (e.g. "johnsmith@upi").
     * Used to resolve the payee during UPI payments.
     */
    @Column(name = "upi_id", nullable = false, unique = true)
    private String upiId;

    /**
     * Simulated bank account number — 12 uppercase hex chars derived from a UUID.
     * Example: "A3F7B2C1D4E9"
     * Auto-generated in @PrePersist if not set.
     */
    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    /** Simulated bank name */
    @Builder.Default
    @Column(name = "bank_name", nullable = false)
    private String bankName = "Fraud Detection Bank";

    /** Simulated IFSC code */
    @Builder.Default
    @Column(name = "ifsc", nullable = false)
    private String ifsc = "FRDTB0001";

    /**
     * Current balance in primary currency unit (INR).
     * Precision: 15 digits total, 2 decimal places.
     * New accounts seeded with 10,000.00 by default.
     */
    @Builder.Default
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance = new BigDecimal("10000.00");

    /**
     * Optimistic locking — guards against ABA problems in concurrent updates.
     * The primary concurrency guard is the pessimistic write lock in the repository.
     */
    @Version
    private Long version;

    /** When this account was created */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.accountNumber == null || this.accountNumber.isBlank()) {
            this.accountNumber = generateAccountNumber();
        }
    }

    /**
     * Generate a 12-character uppercase account number from the first 12 hex
     * characters of a random UUID. This is collision-resistant and unpredictable,
     * unlike the previous hash-based approach which was deterministic from userId.
     */
    private String generateAccountNumber() {
        return UUID.randomUUID()
                   .toString()
                   .replace("-", "")
                   .substring(0, 12)
                   .toUpperCase();
    }
}
