package com.fraud.detection.service;

import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.FraudResult.FraudStatus;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.model.UserTransactionHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AiFraudAnalysisService — Spring AI / OpenAI GPT Fraud Analysis
 *
 * This is Layer 3 of the fraud detection pipeline. It is invoked only when
 * Layers 1 and 2 (rule engine + Redis history) produce no hard FRAUD verdict.
 *
 * Responsibilities:
 *  1. Build a structured, deterministic prompt with full transaction context
 *  2. Call OpenAI GPT via Spring AI ChatClient
 *  3. Parse the machine-parseable response (VERDICT / CONFIDENCE / REASON)
 *  4. Map AI confidence to FraudStatus using the configurable review band:
 *       confidence > reviewConfidenceMax  → FRAUD
 *       confidence in [min, max]          → REVIEW  (human review required)
 *       confidence < reviewConfidenceMin  → SAFE
 *
 * CHANGE LOG:
 *  v1.1 — Completely rewrote prompt for deterministic, machine-parseable output
 *        — Added REVIEW verdict band driven by FraudRulesProperties
 *        — Prompt now includes all 5 fraud dimensions (amount, location, device,
 *          frequency, merchant category)
 *        — Injected FraudRulesProperties for configurable confidence thresholds
 *        — Improved fallback handling: AI unavailable → REVIEW (not SAFE)
 *        — Added reviewNotes population when AI returns REVIEW
 */
@Service
public class AiFraudAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiFraudAnalysisService.class);

    /** Maximum time to wait for an OpenAI response before timing out */
    private static final int AI_TIMEOUT_SECONDS = 5;

    /**
     * Dedicated fixed thread pool for OpenAI calls.
     *
     * WHY NOT CompletableFuture's default ForkJoinPool:
     *  - ForkJoinPool is shared across the entire JVM (Spring internals, @Async, etc.)
     *  - AI calls are I/O-bound and can block for seconds — starving compute tasks
     *  - This dedicated pool gives the AI 10 threads, isolated from the rest of the app
     *
     * Sizing: 10 threads supports up to 10 concurrent AI calls before queuing.
     * With AI_TIMEOUT_SECONDS=5 and 3 Kafka partitions, 10 threads provides
     * comfortable headroom for burst processing.
     */
    private static final ExecutorService AI_EXECUTOR = Executors.newFixedThreadPool(10);

    // ─── Injected Dependencies ─────────────────────────────────────────────

    /**
     * Spring AI ChatClient — auto-configured by spring-ai-openai-spring-boot-starter.
     * Requires OPENAI_API_KEY environment variable or application.yml entry.
     */
    @Autowired
    private ChatClient chatClient;

    /**
     * Fraud rules configuration — provides configurable confidence thresholds.
     * Avoids hardcoded 0.4 / 0.6 magic numbers in this class.
     */
    @Autowired
    private FraudRulesProperties fraudRules;

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Runs AI-based fraud analysis on the given transaction.
     *
     * The method:
     *   1. Builds a rich, structured prompt
     *   2. Calls OpenAI GPT (gpt-3.5-turbo or gpt-4o)
     *   3. Parses the response into verdict + confidence + reason
     *   4. Maps confidence to FraudStatus (SAFE / REVIEW / FRAUD)
     *
     * Failure policy: if AI is unavailable, return REVIEW (not SAFE).
     * A borderline unknown should be reviewed by a human, not auto-approved.
     *
     * @param event    The transaction to analyze
     * @param history  User's Redis-cached history (may be null for new users)
     * @return         FraudResult with SAFE | REVIEW | FRAUD
     */
    public FraudResult analyze(TransactionEvent event, UserTransactionHistory history) {
        log.info("[AI SERVICE] Initiating OpenAI analysis for transaction: {} | User: {}",
                event.getTransactionId(), event.getUserId());

        try {
            // Build the structured prompt
            String prompt = buildStructuredPrompt(event, history);

            log.debug("[AI SERVICE] Sending prompt to OpenAI (length: {} chars)", prompt.length());

            // Invoke OpenAI via Spring AI wrapped in a CompletableFuture with a hard timeout.
            // Uses AI_EXECUTOR (dedicated fixed pool) instead of the default ForkJoinPool
            // to isolate I/O-bound AI blocking from the rest of the application's compute tasks.
            final String transactionId = event.getTransactionId();
            String rawResponse = CompletableFuture
                    .supplyAsync(() -> chatClient.prompt()
                            .user(prompt)
                            .call()
                            .content(),
                            AI_EXECUTOR)   // dedicated pool — not ForkJoinPool.commonPool()
                    .get(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Defensive guard: treat null or blank response as a failure
            if (rawResponse == null || rawResponse.isBlank()) {
                log.warn("[AI SERVICE] Empty/null response from OpenAI for transaction: {} — returning REVIEW",
                        transactionId);
                return buildReviewFallback(event, "AI_EMPTY_RESPONSE",
                        "OpenAI returned an empty response. Transaction held for manual review.");
            }

            log.info("[AI SERVICE] OpenAI responded for transaction: {}", transactionId);
            log.debug("[AI SERVICE] Raw response: {}", rawResponse);

            // Parse + classify the response
            return parseAndClassify(event, rawResponse);

        } catch (TimeoutException e) {
            log.warn("[AI SERVICE] ⏱ TIMEOUT — OpenAI did not respond within {}s for transaction: {} | "
                    + "Returning REVIEW (fail-safe).",
                    AI_TIMEOUT_SECONDS, event.getTransactionId());
            return buildReviewFallback(event, "AI_TIMEOUT",
                    "OpenAI did not respond within " + AI_TIMEOUT_SECONDS
                            + "s. Transaction held for manual review.");

        } catch (Exception e) {
            // AI unavailable, rate-limited, interrupted, or returned an unparseable response.
            // Always return REVIEW — never SAFE. Fail-safe, not fail-open.
            log.error("[AI SERVICE] ✗ FAILED — Transaction: {} | Error: {} | Class: {}",
                    event.getTransactionId(), e.getMessage(), e.getClass().getSimpleName(), e);
            return buildReviewFallback(event, "AI_FALLBACK",
                    "OpenAI unavailable or error: " + e.getMessage()
                            + ". Transaction held for manual review.");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FALLBACK BUILDER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Central REVIEW fallback builder used by all failure paths.
     *
     * Guarantees that every failure (timeout, exception, empty response) returns
     * a consistent REVIEW result — NEVER SAFE. This enforces the fail-safe policy:
     * when AI cannot reach a confident verdict, the transaction must be human-reviewed.
     *
     * @param event           The transaction under analysis
     * @param detectionLayer  Label identifying the failure type (AI_TIMEOUT, AI_FALLBACK, etc.)
     * @param reviewNotes     Human-readable explanation for the analyst
     * @return                FraudResult(REVIEW) with confidence 0.5
     */
    private FraudResult buildReviewFallback(TransactionEvent event,
                                            String detectionLayer,
                                            String reviewNotes) {
        return FraudResult.builder()
                .transactionId(event.getTransactionId())
                .userId(event.getUserId())
                .status(FraudStatus.REVIEW)
                .reason("AI analysis could not complete — transaction held for manual review")
                .confidenceScore(0.5)   // Neutral — neither SAFE nor FRAUD confidence
                .detectionLayer(detectionLayer)
                .reviewNotes(reviewNotes)
                .analyzedAt(LocalDateTime.now())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PROMPT ENGINEERING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds a structured, deterministic prompt for OpenAI GPT.
     *
     * Design principles applied:
     *  1. Role assignment    — "You are a fraud detection AI" primes the model
     *  2. Structured input   — labelled fields prevent misinterpretation
     *  3. Contextual history — user's recent transactions give the model baseline behaviour
     *  4. Explicit criteria  — lists exactly what to check (5 fraud dimensions)
     *  5. Strict format      — VERDICT/CONFIDENCE/REASON prevents free-form prose
     *  6. Temperature = 0.1  — low randomness for consistent decisions (set in application.yml)
     *
     * @param event    The transaction to evaluate
     * @param history  User's recent transaction history from Redis
     * @return         Complete prompt string ready for OpenAI
     */
    private String buildStructuredPrompt(TransactionEvent event, UserTransactionHistory history) {
        StringBuilder sb = new StringBuilder(1500);

        // ── System role instruction ──────────────────────────────────────
        sb.append("You are a fraud detection AI specialised in real-time payment analysis.\n");
        sb.append("Your task is to evaluate whether the following transaction is SAFE, FRAUD, or requires REVIEW.\n\n");

        // ── Transaction under analysis ───────────────────────────────────
        sb.append("=== TRANSACTION TO ANALYSE ===\n");
        sb.append(String.format("Transaction ID  : %s%n", event.getTransactionId()));
        sb.append(String.format("User ID         : %s%n", event.getUserId()));
        sb.append(String.format("Amount (INR)    : %.2f%n", event.getAmount()));
        sb.append(String.format("Location        : %s%n", event.getLocation()));
        sb.append(String.format("Device          : %s%n", event.getDevice()));
        sb.append(String.format("Merchant Cat.   : %s%n",
                event.getMerchantCategory() != null ? event.getMerchantCategory() : "Not provided"));
        sb.append(String.format("Timestamp       : %s%n", event.getTimestamp()));
        sb.append("\n");

        // ── User behaviour context from Redis ────────────────────────────
        sb.append("=== USER BEHAVIOUR CONTEXT ===\n");
        if (history != null
                && history.getRecentTransactions() != null
                && !history.getRecentTransactions().isEmpty()) {

            int txCount = history.getRecentTransactions().size();
            sb.append(String.format("Transactions in 24h window : %d%n", txCount));
            sb.append(String.format("Known locations             : %s%n",
                    history.getKnownLocations() != null ? history.getKnownLocations() : "[]"));

            // Last 3 transactions — enough for pattern recognition without token overload
            sb.append("Recent transactions (latest first):\n");
            int startIdx = Math.max(0, txCount - 3);   // Up to last 3
            for (int i = txCount - 1; i >= startIdx; i--) {
                var t = history.getRecentTransactions().get(i);
                sb.append(String.format("  [%d] Amount: %.2f INR | Location: %s | Device: %s | Time: %s%n",
                        (txCount - i),
                        t.getAmount(),
                        t.getLocation(),
                        t.getDevice(),
                        t.getTimestamp()));
            }
        } else {
            sb.append("No prior transaction history found (new user or first transaction in 24h window).\n");
        }
        sb.append("\n");

        // ── Fraud evaluation criteria ─────────────────────────────────────
        sb.append("=== FRAUD EVALUATION CRITERIA ===\n");
        sb.append("Evaluate the transaction against ALL of the following dimensions:\n");
        sb.append("1. AMOUNT ANOMALY    — Is the amount unusually high compared to the user's history?\n");
        sb.append("2. LOCATION ANOMALY  — Is this location new or inconsistent with known locations?\n");
        sb.append("3. DEVICE ANOMALY    — Is the device different from those previously used?\n");
        sb.append("4. VELOCITY ANOMALY  — Is the transaction frequency within the 24h window unusual?\n");
        sb.append("5. CATEGORY ANOMALY  — Does the merchant category match the user's normal behaviour?\n\n");

        // ── Verdict definitions ──────────────────────────────────────────
        sb.append("=== VERDICT DEFINITIONS ===\n");
        sb.append("SAFE   → Transaction shows no suspicious indicators. Approve.\n");
        sb.append("FRAUD  → Transaction shows strong fraud signals. Block immediately.\n");
        sb.append("REVIEW → Transaction shows soft anomalies. Hold for human analyst review.\n\n");

        // ── Required output format — CRITICAL for machine parsing ─────────
        sb.append("=== REQUIRED RESPONSE FORMAT ===\n");
        sb.append("Respond with EXACTLY these three lines and NO other text:\n");
        sb.append("VERDICT: [SAFE or FRAUD or REVIEW]\n");
        sb.append("CONFIDENCE: [decimal between 0.00 and 1.00 — your confidence that this is FRAUD]\n");
        sb.append("REASON: [one concise sentence explaining your verdict, max 30 words]\n\n");
        sb.append("IMPORTANT RULES:\n");
        sb.append("- Do NOT add explanations, preambles, or extra lines\n");
        sb.append("- CONFIDENCE must be a decimal number only (e.g., 0.87)\n");
        sb.append("- If confidence is between ")
                .append(fraudRules.getReviewConfidenceMin())
                .append(" and ")
                .append(fraudRules.getReviewConfidenceMax())
                .append(", use REVIEW\n");

        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  RESPONSE PARSING + STATUS CLASSIFICATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Parses the OpenAI response and maps it to a FraudResult.
     *
     * Expected response (3 lines, strict format):
     *   VERDICT: FRAUD
     *   CONFIDENCE: 0.92
     *   REASON: Unusually high amount from unrecognised device in new location.
     *
     * Confidence-to-status mapping (driven by FraudRulesProperties):
     *   confidence > reviewConfidenceMax  → FRAUD   (e.g., > 0.60)
     *   confidence in [min, max]          → REVIEW  (e.g., 0.40–0.60)
     *   confidence < reviewConfidenceMin  → SAFE    (e.g., < 0.40)
     *
     * Note: The AI's text VERDICT is used as a primary signal, but the
     * confidence-band mapping overrides it to ensure consistent classification.
     *
     * @param event      Source transaction (for building the result)
     * @param rawResponse  Raw string from OpenAI
     * @return           FraudResult with final SAFE | REVIEW | FRAUD verdict
     */
    private FraudResult parseAndClassify(TransactionEvent event, String rawResponse) {
        // Default values in case parsing partially fails
        String aiVerdict    = "UNKNOWN";
        double confidence   = 0.5;
        String reason       = "AI analysis completed — see confidence score";

        try {
            String[] lines = rawResponse.trim().split("\\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("VERDICT:")) {
                    aiVerdict = line.replace("VERDICT:", "").trim().toUpperCase();
                } else if (line.startsWith("CONFIDENCE:")) {
                    String confStr = line.replace("CONFIDENCE:", "").trim();
                    confidence = Double.parseDouble(confStr);
                    // Strict clamp: GPT occasionally returns values slightly outside [0,1]
                    // (e.g., 1.01 or -0.02). Hard-clamp to prevent classification errors.
                    confidence = Math.max(0.0, Math.min(1.0, confidence));
                } else if (line.startsWith("REASON:")) {
                    reason = line.replace("REASON:", "").trim();
                }
            }
        } catch (Exception e) {
            log.warn("[AI SERVICE] Partial parse failure for transaction: {} — Error: {}. "
                    + "Raw response: [{}]",
                    event.getTransactionId(), e.getMessage(),
                    rawResponse.substring(0, Math.min(rawResponse.length(), 300)));
            // Continue with whatever values were extracted before the error
        }

        // ── Confidence-band classification ───────────────────────────────
        // The confidence score drives the final status.
        // This is more reliable than the text VERDICT because it's continuous.
        FraudStatus finalStatus;
        String reviewNotes = null;

        if (confidence > fraudRules.getReviewConfidenceMax()) {
            // High confidence in fraud
            finalStatus = FraudStatus.FRAUD;
            log.warn("[AI SERVICE] → FRAUD | Transaction: {} | Confidence: {} | Reason: {}",
                    event.getTransactionId(), confidence, reason);

        } else if (confidence >= fraudRules.getReviewConfidenceMin()) {
            // Borderline — neither confident SAFE nor confident FRAUD
            finalStatus = FraudStatus.REVIEW;
            reviewNotes = String.format(
                    "AI confidence score %.2f falls in the uncertain band [%.2f – %.2f]. "
                            + "AI verdict was '%s'. Human analyst review required. "
                            + "AI reasoning: %s",
                    confidence,
                    fraudRules.getReviewConfidenceMin(),
                    fraudRules.getReviewConfidenceMax(),
                    aiVerdict,
                    reason);
            log.info("[AI SERVICE] → REVIEW | Transaction: {} | Confidence: {} (band: {}-{})",
                    event.getTransactionId(), confidence,
                    fraudRules.getReviewConfidenceMin(),
                    fraudRules.getReviewConfidenceMax());

        } else {
            // Low confidence in fraud — safe to approve
            finalStatus = FraudStatus.SAFE;
            log.info("[AI SERVICE] → SAFE | Transaction: {} | Confidence: {} | Reason: {}",
                    event.getTransactionId(), confidence, reason);
        }

        return FraudResult.builder()
                .transactionId(event.getTransactionId())
                .userId(event.getUserId())
                .status(finalStatus)
                .reason(reason)
                .confidenceScore(confidence)
                .detectionLayer("AI")
                .reviewNotes(reviewNotes)
                .analyzedAt(LocalDateTime.now())
                .build();
    }
}
