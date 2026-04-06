package com.fraud.detection.model;

/**
 * PaymentStatus — all possible states for a payment lifecycle after fraud check.
 *
 * State machine:
 *
 *   PENDING ──────────────────────────────→ SUCCESS
 *      │                                       (debit + credit both succeeded)
 *      │
 *      ├──────────────────────────────────→ FAILED
 *      │                                       (debit failed; no money moved)
 *      │
 *      ├── debit OK → credit FAIL ────────→ COMPENSATING
 *      │                                       (attempting refund to payer)
 *      │        ├──────────────────────────→ COMPENSATED
 *      │        │                               (refund succeeded; net = zero movement)
 *      │        └──────────────────────────→ COMPENSATION_FAILED
 *      │                                       (refund ALSO failed — CRITICAL, manual action required)
 *      │
 *      └──────────────────────────────────→ SKIPPED
 *                                              (legacy tx with no payeeUpiId, or non-UPI payment)
 *
 * RAZORPAY mode adds:
 *   PENDING → RAZORPAY_ORDER_CREATED (client must complete via Razorpay SDK)
 *           → FAILED (order creation failed; fell back to BANK or aborted)
 */
public enum PaymentStatus {

    /** Payment is being processed — idempotency lock is held */
    PENDING,

    /** Debit from payer + credit to payee both completed successfully */
    SUCCESS,

    /** Payment failed before any money moved (debit failed, payee not found, etc.) */
    FAILED,

    /** Debit succeeded; now attempting credit reversal (payer refund) */
    COMPENSATING,

    /** Credit failed; compensating debit-reversal (refund) succeeded — net: no money moved */
    COMPENSATED,

    /**
     * CRITICAL: debit succeeded, credit failed, AND the compensating refund also failed.
     * Money has left the payer's account but not reached the payee.
     * Requires IMMEDIATE manual reconciliation by operations team.
     */
    COMPENSATION_FAILED,

    /** Razorpay order created — client must complete payment via Razorpay SDK */
    RAZORPAY_ORDER_CREATED,

    /**
     * Transaction was not a UPI payment (legacy format, no payeeUpiId).
     * No payment processing was attempted.
     */
    SKIPPED
}
