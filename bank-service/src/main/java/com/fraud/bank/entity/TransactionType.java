package com.fraud.bank.entity;

/**
 * TransactionType — type of bank ledger entry recorded in BankTransaction.
 *
 * Each enum value corresponds to a distinct idempotency key when combined
 * with a transactionId, so DEBIT and REFUND for the same txId are independent.
 */
public enum TransactionType {

    /** Money deducted from payer — triggered by post-fraud SAFE verdict */
    DEBIT,

    /** Money added to payee — triggered by post-fraud SAFE verdict */
    CREDIT,

    /**
     * Payer refunded after a credit failure — triggered by saga compensation.
     * Uses the same transactionId as the original payment but a different type,
     * so idempotency is tracked independently from DEBIT.
     */
    REFUND
}
