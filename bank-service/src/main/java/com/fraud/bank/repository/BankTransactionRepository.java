package com.fraud.bank.repository;

import com.fraud.bank.entity.BankTransaction;
import com.fraud.bank.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * BankTransactionRepository — persistence for bank ledger entries.
 *
 * The existsByTransactionIdAndType() method is the primary idempotency check
 * used by BankService before applying any debit, credit, or refund. The DB
 * unique constraint on (transaction_id, type) in BankTransaction is the
 * authoritative safety net that catches race conditions the application-layer
 * check may miss.
 */
@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    /**
     * Check whether a bank operation of the given type has already been applied
     * for the given transaction ID.
     *
     * Used by BankService as an idempotency gate before debit/credit/refund.
     *
     * @param transactionId the payment transaction UUID
     * @param type          DEBIT, CREDIT, or REFUND
     * @return true if the operation was already applied, false otherwise
     */
    boolean existsByTransactionIdAndType(String transactionId, TransactionType type);
}
