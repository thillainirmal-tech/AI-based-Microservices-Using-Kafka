package com.fraud.detection.model;

import com.fraud.common.dto.TransactionEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder.Default;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * UserTransactionHistory — Redis Cached Model
 *
 * Stored in Redis under key: "user:{userId}:history"
 * TTL: 24 hours (configured in RedisService)
 *
 * Holds the last N transactions per user.
 * Used by FraudDetectionService to:
 *  - Detect unusual transaction frequency
 *  - Compare current location vs known locations
 *  - Identify patterns inconsistent with normal behaviour
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserTransactionHistory implements Serializable {

    /** User ID — matches userId in TransactionEvent */
    private String userId;

    /**
     * List of recent transactions.
     * Eagerly initialised — never null, eliminates defensive null checks throughout the codebase.
     * Capped at MAX_HISTORY_SIZE to prevent unbounded growth.
     * @Builder.Default ensures the Lombok builder respects this initialiser.
     */
    @Default
    private List<TransactionEvent> recentTransactions = new ArrayList<>();

    /**
     * Set of known locations for this user (insertion-ordered, no duplicates).
     * Eagerly initialised — LinkedHashSet gives O(1) contains() and preserves insertion order.
     * @Builder.Default ensures the Lombok builder respects this initialiser.
     */
    @Default
    private Set<String> knownLocations = new LinkedHashSet<>();

    /** Maximum number of transactions to keep per user */
    public static final int MAX_HISTORY_SIZE = 20;

    /**
     * Adds a new transaction to the history.
     * Maintains the rolling window of MAX_HISTORY_SIZE.
     * No null check on recentTransactions — eagerly initialised by @Builder.Default.
     */
    public void addTransaction(TransactionEvent event) {
        recentTransactions.add(event);

        // Keep only the latest MAX_HISTORY_SIZE transactions
        if (recentTransactions.size() > MAX_HISTORY_SIZE) {
            recentTransactions.remove(0);  // Remove oldest
        }

        // Register location as known
        addKnownLocation(event.getLocation());
    }

    /**
     * Registers a location as known for this user.
     * No null check on knownLocations — eagerly initialised by @Builder.Default.
     * Set.add() is idempotent — duplicates are silently ignored.
     */
    public void addKnownLocation(String location) {
        if (location != null) {
            knownLocations.add(location);
        }
    }

    /**
     * Returns true if this user has never transacted from the given location.
     * Set.contains() is O(1). No null check needed — always initialised.
     */
    public boolean isUnknownLocation(String location) {
        return !knownLocations.contains(location);
    }
}
