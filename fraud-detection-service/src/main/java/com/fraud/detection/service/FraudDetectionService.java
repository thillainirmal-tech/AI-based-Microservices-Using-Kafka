package com.fraud.detection.service;

import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.FraudResult.FraudStatus;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.model.UserTransactionHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * FraudDetectionService — Core Fraud Analysis Orchestrator
 *
 * Implements a 3-layer short-circuit pipeline:
 *
 *  ╔══════════════════════════════════════════════════════╗
 *  ║  LAYER 1 — Rule Engine (deterministic, <1ms)        ║
 *  ║  ├─ Amount threshold   → FRAUD if above limit       ║
 *  ║  ├─ Unknown location   → FRAUD if new for user      ║
 *  ║  └─ Transaction count  → FRAUD if velocity exceeded ║
 *  ╠══════════════════════════════════════════════════════╣
 *  ║  LAYER 2 — Redis History Analyser (contextual)      ║
 *  ║  ├─ Impossible travel  → FRAUD (2 cities < Nmin)   ║
 *  ║  └─ Device change      → REVIEW (soft flag to AI)  ║
 *  ╠══════════════════════════════════════════════════════╣
 *  ║  LAYER 3 — Spring AI / OpenAI GPT                  ║
 *  ║  ├─ confidence > 0.6   → FRAUD                     ║
 *  ║  ├─ confidence 0.4–0.6 → REVIEW (human review)     ║
 *  ║  └─ confidence < 0.4   → SAFE                      ║
 *  ╚══════════════════════════════════════════════════════╝
 *
 * Short-circuit: each layer returns immediately on FRAUD detection;
 * REVIEW from Layer 2 escalates to AI for a final verdict.
 *
 * CHANGE LOG:
 *  v1.1 — Removed ALL hardcoded thresholds; injected FraudRulesProperties
 *        — Device change now produces REVIEW (not silently ignored)
 *        — AI confidence band drives REVIEW vs SAFE vs FRAUD classification
 *        — Added reviewNotes population when status = REVIEW
 *        — Improved structured logging at every decision point
 */
@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    // ─── Injected Dependencies ─────────────────────────────────────────────

    /**
     * Externalised fraud rule thresholds — all values come from application.yml.
     * No magic numbers exist in this class.
     *
     * Example values:
     *   fraudRules.getHighAmountThreshold()   = 10000 INR
     *   fraudRules.getMaxTransactionsPerDay() = 10
     *   fraudRules.getImpossibleTravelMinutes() = 5
     */
    @Autowired
    private FraudRulesProperties fraudRules;

    /** Redis service — user history reads + result persistence */
    @Autowired
    private RedisService redisService;

    /** Spring AI service — OpenAI GPT integration */
    @Autowired
    private AiFraudAnalysisService aiFraudAnalysisService;

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Runs the full 3-layer fraud detection pipeline for a single transaction.
     *
     * Called by:
     *   - TransactionConsumer (Kafka listener, async path)
     *   - FraudController.analyzeDirectly() (synchronous test path)
     *
     * @param event  The transaction received from Kafka (or direct API call)
     * @return       FraudResult with status SAFE | FRAUD | REVIEW
     */
    public FraudResult analyzeTransaction(TransactionEvent event) {
        long startMs = System.currentTimeMillis();

        log.info("[FRAUD ENGINE] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("[FRAUD ENGINE] START — Transaction: {} | User: {} | Amount: {} | Location: {} | Device: {}",
                event.getTransactionId(),
                event.getUserId(),
                event.getAmount(),
                event.getLocation(),
                event.getDevice());

        // Fetch existing user history ONCE before any layer runs.
        // Layer 1 (unknown-location rule) and Layer 2 (history analysis) both need it.
        // Previously, applyRules() called getHistory() internally and recordTransaction()
        // also called getHistory() — two round-trips to Redis for the same data.
        UserTransactionHistory existingHistory = redisService.getHistory(event.getUserId());

        // ─── LAYER 1: Rule-Based Checks ──────────────────────────────────
        // Fast, deterministic — runs before any additional Redis or AI call
        FraudResult ruleResult = applyRules(event, existingHistory);
        if (ruleResult != null) {
            // Record transaction in history even if FRAUD (for future velocity/travel analysis)
            redisService.recordTransaction(event);
            log.warn("[FRAUD ENGINE] RULE FRAUD → Transaction: {} flagged by rule engine | Elapsed: {}ms",
                    event.getTransactionId(), System.currentTimeMillis() - startMs);
            return ruleResult;
        }

        // ─── LAYER 2: Redis History Analysis ─────────────────────────────
        // Record transaction and fetch enriched history (now includes current transaction)
        UserTransactionHistory history = redisService.recordTransaction(event);
        FraudResult historyResult = analyzeHistory(event, history);

        if (historyResult != null && historyResult.getStatus() == FraudStatus.FRAUD) {
            // Hard FRAUD from history analysis — no need for AI
            log.warn("[FRAUD ENGINE] HISTORY FRAUD → Transaction: {} | Reason: {} | Elapsed: {}ms",
                    event.getTransactionId(), historyResult.getReason(),
                    System.currentTimeMillis() - startMs);
            return historyResult;
        }

        // REVIEW from history (e.g., device change) — escalate to AI with context
        boolean hasHistorySoftFlag = (historyResult != null
                && historyResult.getStatus() == FraudStatus.REVIEW);

        if (hasHistorySoftFlag) {
            log.info("[FRAUD ENGINE] HISTORY REVIEW → escalating {} to AI. Soft flag: {}",
                    event.getTransactionId(), historyResult.getReason());
        }

        // ─── LAYER 3: AI Analysis ─────────────────────────────────────────
        log.info("[FRAUD ENGINE] AI LAYER → sending transaction {} to OpenAI",
                event.getTransactionId());
        FraudResult aiResult = aiFraudAnalysisService.analyze(event, history);

        // If history flagged a soft anomaly and AI says SAFE, bump to REVIEW
        // so the borderline case is not silently cleared.
        if (hasHistorySoftFlag && aiResult.getStatus() == FraudStatus.SAFE) {
            log.info("[FRAUD ENGINE] Upgrading AI SAFE to REVIEW — history soft flag was present");
            aiResult = aiResult.toBuilder()
                    .status(FraudStatus.REVIEW)
                    .reviewNotes(historyResult.getReason()
                            + " | AI returned SAFE but history flag overrides to REVIEW")
                    .build();
        }

        log.info("[FRAUD ENGINE] FINAL → Transaction: {} | Status: {} | Confidence: {} | Layer: {} | Elapsed: {}ms",
                event.getTransactionId(),
                aiResult.getStatus(),
                aiResult.getConfidenceScore(),
                aiResult.getDetectionLayer(),
                System.currentTimeMillis() - startMs);
        log.info("[FRAUD ENGINE] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        return aiResult;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LAYER 1 — Rule-Based Detection
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Applies three deterministic fraud rules in order of severity.
     *
     * Rules are evaluated sequentially and short-circuit on first violation.
     * All threshold values are read from FraudRulesProperties — no hardcoding.
     *
     * @param event           The transaction to check
     * @param existingHistory Pre-fetched user history (may be null for new users).
     *                        Passed in to avoid a redundant Redis round-trip — the
     *                        caller already fetched this before invoking Layer 1.
     * @return                FraudResult(FRAUD) on first rule violation; null if all pass
     */
    private FraudResult applyRules(TransactionEvent event, UserTransactionHistory existingHistory) {

        // ── Rule 1: High Amount Threshold ──────────────────────────────
        // Configurable via: fraud.rules.high-amount-threshold (default: 10000)
        if (event.getAmount().compareTo(fraudRules.getHighAmountThreshold()) > 0) {
            log.warn("[RULE ENGINE] HIGH AMOUNT — Transaction: {} | Amount: {} > Threshold: {}",
                    event.getTransactionId(),
                    event.getAmount(),
                    fraudRules.getHighAmountThreshold());

            return buildResult(event,
                    FraudStatus.FRAUD,
                    String.format("Amount %.2f INR exceeds the high-risk threshold of %.2f INR",
                            event.getAmount(), fraudRules.getHighAmountThreshold()),
                    1.0,
                    "RULE_BASED",
                    null);
        }

        // ── Rule 2: Unknown Location ───────────────────────────────────
        // Only checked when user has existing history (new users are not flagged here)
        if (existingHistory != null && existingHistory.isUnknownLocation(event.getLocation())) {
            log.warn("[RULE ENGINE] UNKNOWN LOCATION — User: {} | New: {} | Known: {}",
                    event.getUserId(),
                    event.getLocation(),
                    existingHistory.getKnownLocations());

            return buildResult(event,
                    FraudStatus.FRAUD,
                    String.format("Transaction from unrecognised location '%s'. "
                                    + "User's known locations: %s",
                            event.getLocation(),
                            existingHistory.getKnownLocations()),
                    0.9,
                    "RULE_BASED",
                    null);
        }

        // ── Rule 3: Transaction Velocity (frequency check) ─────────────
        // Configurable via: fraud.rules.max-transactions-per-day (default: 10)
        //
        // Optimization: use existingHistory.size() instead of redisService.getTransactionCount().
        // The history was already fetched from Redis at the top of analyzeTransaction() —
        // calling getTransactionCount() would be a second Redis round-trip for the same data.
        int txCount = (existingHistory != null && existingHistory.getRecentTransactions() != null)
                ? existingHistory.getRecentTransactions().size()
                : 0;
        if (txCount >= fraudRules.getMaxTransactionsPerDay()) {
            log.warn("[RULE ENGINE] HIGH VELOCITY — User: {} | Count: {} >= Limit: {}",
                    event.getUserId(),
                    txCount,
                    fraudRules.getMaxTransactionsPerDay());

            return buildResult(event,
                    FraudStatus.FRAUD,
                    String.format("Transaction velocity exceeded: %d transactions in 24h window "
                                    + "(limit: %d). Possible card compromise or bot activity.",
                            txCount,
                            fraudRules.getMaxTransactionsPerDay()),
                    0.85,
                    "RULE_BASED",
                    null);
        }

        log.debug("[RULE ENGINE] PASSED — All rules cleared for transaction: {}",
                event.getTransactionId());
        return null;  // No violation — proceed to Layer 2
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LAYER 2 — Redis History Analysis
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Analyses the user's cached transaction history for behavioural anomalies.
     *
     * Returns:
     *   FRAUD  — impossible travel detected (hard block)
     *   REVIEW — device change detected (soft flag → escalate to AI)
     *   null   — no anomaly found
     *
     * @param event    The current transaction being processed
     * @param history  User's Redis-cached history (already includes current event)
     * @return         FraudResult or null
     */
    private FraudResult analyzeHistory(TransactionEvent event, UserTransactionHistory history) {
        if (history == null
                || history.getRecentTransactions() == null
                || history.getRecentTransactions().size() < 2) {
            log.debug("[HISTORY ENGINE] Insufficient history for user: {} — skipping",
                    event.getUserId());
            return null;
        }

        List<TransactionEvent> recent = history.getRecentTransactions();
        // Index -2 is the transaction BEFORE the current one
        TransactionEvent previous = recent.get(recent.size() - 2);

        // ── History Check 1: Impossible Travel ────────────────────────
        // Configurable via: fraud.rules.impossible-travel-minutes (default: 5)
        if (isDifferentLocation(previous, event)) {
            long minutesBetween = getMinutesBetween(previous, event);
            if (minutesBetween >= 0 && minutesBetween < fraudRules.getImpossibleTravelMinutes()) {
                log.warn("[HISTORY ENGINE] IMPOSSIBLE TRAVEL — User: {} | {} → {} | Gap: {}min | Limit: {}min",
                        event.getUserId(),
                        previous.getLocation(),
                        event.getLocation(),
                        minutesBetween,
                        fraudRules.getImpossibleTravelMinutes());

                return buildResult(event,
                        FraudStatus.FRAUD,
                        String.format("Impossible travel: transactions from '%s' and '%s' "
                                        + "within %d minutes. Limit: %d minutes.",
                                previous.getLocation(),
                                event.getLocation(),
                                minutesBetween,
                                fraudRules.getImpossibleTravelMinutes()),
                        0.95,
                        "REDIS_HISTORY",
                        null);
            }
        }

        // ── History Check 2: Device Change ────────────────────────────
        // A sudden device change is suspicious but not definitive.
        // Flag as REVIEW and let the AI make the final call.
        if (isDifferentDevice(previous, event)) {
            log.info("[HISTORY ENGINE] DEVICE CHANGE — User: {} | Previous: {} | Current: {} → REVIEW",
                    event.getUserId(), previous.getDevice(), event.getDevice());

            String reviewNote = String.format(
                    "Device change detected: previous transaction used '%s', "
                            + "current transaction uses '%s'. "
                            + "This may indicate account sharing or credential compromise.",
                    previous.getDevice(),
                    event.getDevice());

            return buildResult(event,
                    FraudStatus.REVIEW,
                    "Soft anomaly: device change between consecutive transactions",
                    0.5,          // Neutral confidence — AI will refine this
                    "REDIS_HISTORY",
                    reviewNote);
        }

        log.debug("[HISTORY ENGINE] PASSED — No anomalies found for transaction: {}",
                event.getTransactionId());
        return null;  // Clean — proceed to AI
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Private Helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns true if the two transactions are from different locations
     * AND both location values are non-null/non-blank.
     */
    private boolean isDifferentLocation(TransactionEvent previous, TransactionEvent current) {
        return previous.getLocation() != null
                && current.getLocation() != null
                && !previous.getLocation().equalsIgnoreCase(current.getLocation());
    }

    /**
     * Returns true if the two transactions used different devices
     * AND both device values are non-null/non-blank.
     */
    private boolean isDifferentDevice(TransactionEvent previous, TransactionEvent current) {
        return previous.getDevice() != null
                && current.getDevice() != null
                && !previous.getDevice().equalsIgnoreCase(current.getDevice());
    }

    /**
     * Returns the absolute number of minutes between two transactions' timestamps.
     * Returns -1 if either timestamp is null (cannot compute).
     */
    private long getMinutesBetween(TransactionEvent a, TransactionEvent b) {
        if (a.getTimestamp() == null || b.getTimestamp() == null) {
            return -1;
        }
        return Duration.between(a.getTimestamp(), b.getTimestamp()).abs().toMinutes();
    }

    /**
     * Central factory method for building FraudResult objects.
     * Eliminates repetition across all detection layers.
     *
     * @param event          Source transaction
     * @param status         SAFE | FRAUD | REVIEW
     * @param reason         Human-readable explanation
     * @param confidence     Score 0.0 – 1.0
     * @param detectionLayer Label of the layer producing this result
     * @param reviewNotes    Additional notes for human analysts (nullable)
     */
    private FraudResult buildResult(TransactionEvent event,
                                    FraudStatus status,
                                    String reason,
                                    double confidence,
                                    String detectionLayer,
                                    String reviewNotes) {
        return FraudResult.builder()
                .transactionId(event.getTransactionId())
                .userId(event.getUserId())
                .status(status)
                .reason(reason)
                .confidenceScore(confidence)
                .detectionLayer(detectionLayer)
                .reviewNotes(reviewNotes)         // null for SAFE/FRAUD, populated for REVIEW
                .analyzedAt(LocalDateTime.now())
                .build();
    }
}
