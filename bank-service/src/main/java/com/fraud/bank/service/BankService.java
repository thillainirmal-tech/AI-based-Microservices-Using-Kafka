package com.fraud.bank.service;

import com.fraud.bank.dto.AccountResponse;
import com.fraud.bank.dto.BalanceResponse;
import com.fraud.bank.dto.CreateAccountRequest;
import com.fraud.bank.dto.DebitCreditRequest;
import com.fraud.bank.entity.BankAccount;
import com.fraud.bank.entity.BankTransaction;
import com.fraud.bank.entity.TransactionType;
import com.fraud.bank.exception.AccountNotFoundException;
import com.fraud.bank.exception.InsufficientFundsException;
import com.fraud.bank.repository.BankAccountRepository;
import com.fraud.bank.repository.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BankService — core banking operations (Polish v3)
 *
 * CRITICAL INVARIANT:
 *   Debit and Credit are ONLY called by fraud-detection-service AFTER a SAFE verdict.
 *   This service does not check fraud — it trusts the caller.
 *   In production, add HMAC or mutual TLS between services.
 *
 * Concurrency safety:
 *   - Both debit() and credit() use pessimistic write lock (SELECT ... FOR UPDATE)
 *     to prevent concurrent balance corruption.
 *   - BankAccount.@Version provides a second layer via optimistic locking.
 *
 * Idempotency:
 *   - BankTransaction records (unique on transactionId + type) ensure debit and credit
 *     are applied exactly once, even on Kafka retry storms.
 *   - Duplicate debit/credit for the same txId → returns 200 with current balance,
 *     no double-debit or double-credit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankService {

    private static final String DEFAULT_CURRENCY = "INR";
    private static final BigDecimal DEFAULT_BALANCE = new BigDecimal("10000.00");

    private final BankAccountRepository    accountRepository;
    private final BankTransactionRepository bankTransactionRepository;

    // ─── Create Account ───────────────────────────────────────────────────────

    /**
     * Create a new bank account for a newly registered user.
     * Called by auth-service immediately after user registration.
     * Seeds the account with a default balance of 10,000 INR.
     *
     * @throws IllegalArgumentException if userId or upiId already has an account
     */
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        if (accountRepository.existsByUserId(request.getUserId())) {
            throw new IllegalArgumentException(
                "Bank account already exists for userId: " + request.getUserId());
        }
        if (accountRepository.existsByUpiId(request.getUpiId())) {
            throw new IllegalArgumentException(
                "Bank account already exists for upiId: " + request.getUpiId());
        }

        BankAccount account = BankAccount.builder()
                .userId(request.getUserId())
                .upiId(request.getUpiId())
                .balance(DEFAULT_BALANCE)
                .build();

        account = accountRepository.save(account);
        log.info("[BANK] Account created: userId={} upiId={} accountNumber={} balance={}",
                 account.getUserId(), account.getUpiId(),
                 account.getAccountNumber(), account.getBalance());

        return toAccountResponse(account, "Account created successfully");
    }

    // ─── Debit ────────────────────────────────────────────────────────────────

    /**
     * Debit (subtract) the given amount from the account.
     *
     * Idempotency: if a DEBIT record already exists for this transactionId, skips
     * the debit and returns the current balance — safe on Kafka retry.
     *
     * Uses a pessimistic write lock to prevent concurrent overdrafts.
     * Called ONLY by fraud-detection-service after a SAFE verdict.
     *
     * @throws AccountNotFoundException   if no account found for userId
     * @throws InsufficientFundsException if balance < amount
     */
    @Transactional
    public AccountResponse debit(DebitCreditRequest request) {
        // Idempotency gate — reject double-debit for same transaction
        if (bankTransactionRepository.existsByTransactionIdAndType(
                request.getTransactionId(), TransactionType.DEBIT)) {
            log.warn("[BANK] Idempotency: DEBIT already applied — txId={} userId={}. Skipping.",
                    request.getTransactionId(), request.getUserId());
            BankAccount account = accountRepository.findByUserId(request.getUserId())
                    .orElseThrow(() -> new AccountNotFoundException(
                        "No bank account found for userId: " + request.getUserId()));
            return toAccountResponse(account, "Debit already applied (idempotent)");
        }

        // Pessimistic write lock — prevents concurrent overdraft
        BankAccount account = accountRepository
                .findByUserIdForUpdate(request.getUserId())
                .orElseThrow(() -> new AccountNotFoundException(
                    "No bank account found for userId: " + request.getUserId()));

        if (account.getBalance().compareTo(request.getAmount()) < 0) {
            log.warn("[BANK] Insufficient funds: userId={} balance={} requested={} txId={}",
                     request.getUserId(), account.getBalance(),
                     request.getAmount(), request.getTransactionId());
            throw new InsufficientFundsException(
                String.format("Insufficient funds. Available: %.2f, Requested: %.2f",
                              account.getBalance(), request.getAmount()));
        }

        BigDecimal newBalance = account.getBalance().subtract(request.getAmount());
        account.setBalance(newBalance);
        accountRepository.save(account);

        // Record the transaction for idempotency
        bankTransactionRepository.save(BankTransaction.builder()
                .transactionId(request.getTransactionId())
                .type(TransactionType.DEBIT)
                .userId(request.getUserId())
                .amount(request.getAmount())
                .balanceAfter(newBalance)
                .appliedAt(LocalDateTime.now())
                .build());

        log.info("[BANK_TX] DEBIT txId={} userId={} amount={} newBalance={}",
                 request.getTransactionId(), request.getUserId(),
                 request.getAmount(), newBalance);

        return toAccountResponse(account, "Debit successful");
    }

    // ─── Credit ───────────────────────────────────────────────────────────────

    /**
     * Credit (add) the given amount to the account.
     *
     * Idempotency: if a CREDIT record already exists for this transactionId, skips
     * the credit and returns the current balance — safe on Kafka retry.
     *
     * Uses a pessimistic write lock (same as debit) to prevent concurrent
     * balance corruption when multiple credits race for the same account.
     * Called ONLY by fraud-detection-service after a SAFE verdict.
     *
     * @throws AccountNotFoundException if no account found for userId
     */
    @Transactional
    public AccountResponse credit(DebitCreditRequest request) {
        // Idempotency gate — reject double-credit for same transaction
        if (bankTransactionRepository.existsByTransactionIdAndType(
                request.getTransactionId(), TransactionType.CREDIT)) {
            log.warn("[BANK] Idempotency: CREDIT already applied — txId={} userId={}. Skipping.",
                    request.getTransactionId(), request.getUserId());
            BankAccount account = accountRepository.findByUserId(request.getUserId())
                    .orElseThrow(() -> new AccountNotFoundException(
                        "No bank account found for userId: " + request.getUserId()));
            return toAccountResponse(account, "Credit already applied (idempotent)");
        }

        // Pessimistic write lock — prevents concurrent balance corruption
        BankAccount account = accountRepository
                .findByUserIdForUpdate(request.getUserId())
                .orElseThrow(() -> new AccountNotFoundException(
                    "No bank account found for userId: " + request.getUserId()));

        BigDecimal newBalance = account.getBalance().add(request.getAmount());
        account.setBalance(newBalance);
        accountRepository.save(account);

        // Record the transaction for idempotency
        bankTransactionRepository.save(BankTransaction.builder()
                .transactionId(request.getTransactionId())
                .type(TransactionType.CREDIT)
                .userId(request.getUserId())
                .amount(request.getAmount())
                .balanceAfter(newBalance)
                .appliedAt(LocalDateTime.now())
                .build());

        log.info("[BANK_TX] CREDIT txId={} userId={} amount={} newBalance={}",
                 request.getTransactionId(), request.getUserId(),
                 request.getAmount(), newBalance);

        return toAccountResponse(account, "Credit successful");
    }

    // ─── Refund ───────────────────────────────────────────────────────────────

    /**
     * Refund — credits the payer back after a failed credit-to-payee.
     * Uses a separate TransactionType (REFUND) so idempotency is independent
     * of the CREDIT operation.
     *
     * Called by the saga compensation flow in fraud-detection-service.
     *
     * @throws AccountNotFoundException if no account found for userId
     */
    @Transactional
    public AccountResponse refund(DebitCreditRequest request) {
        // Idempotency gate
        if (bankTransactionRepository.existsByTransactionIdAndType(
                request.getTransactionId(), TransactionType.REFUND)) {
            log.warn("[BANK] Idempotency: REFUND already applied — txId={} userId={}. Skipping.",
                    request.getTransactionId(), request.getUserId());
            BankAccount account = accountRepository.findByUserId(request.getUserId())
                    .orElseThrow(() -> new AccountNotFoundException(
                        "No bank account found for userId: " + request.getUserId()));
            return toAccountResponse(account, "Refund already applied (idempotent)");
        }

        BankAccount account = accountRepository
                .findByUserIdForUpdate(request.getUserId())
                .orElseThrow(() -> new AccountNotFoundException(
                    "No bank account found for userId: " + request.getUserId()));

        BigDecimal newBalance = account.getBalance().add(request.getAmount());
        account.setBalance(newBalance);
        accountRepository.save(account);

        bankTransactionRepository.save(BankTransaction.builder()
                .transactionId(request.getTransactionId())
                .type(TransactionType.REFUND)
                .userId(request.getUserId())
                .amount(request.getAmount())
                .balanceAfter(newBalance)
                .appliedAt(LocalDateTime.now())
                .build());

        log.info("[BANK_TX] REFUND txId={} userId={} amount={} newBalance={}",
                 request.getTransactionId(), request.getUserId(),
                 request.getAmount(), newBalance);

        return toAccountResponse(account, "Refund successful");
    }

    // ─── Balance ──────────────────────────────────────────────────────────────

    /**
     * Fetch current balance for a user.
     * Called by authenticated users via the API gateway (GET /bank/balance).
     * userId is always sourced from the X-User-Email header (set by gateway
     * from JWT subject) — never from a request parameter.
     *
     * @throws AccountNotFoundException if no account found for userId
     */
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String userId) {
        BankAccount account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new AccountNotFoundException(
                    "No bank account found for userId: " + userId));

        return BalanceResponse.builder()
                .userId(account.getUserId())
                .upiId(account.getUpiId())
                .balance(account.getBalance())
                .currency(DEFAULT_CURRENCY)
                .build();
    }

    // ─── Lookup by UPI ID ─────────────────────────────────────────────────────

    /**
     * Look up an account by UPI ID — used by fraud-detection-service
     * and transaction-service to validate/resolve the payee before payment.
     *
     * @throws AccountNotFoundException if no account found for upiId
     */
    @Transactional(readOnly = true)
    public AccountResponse findByUpiId(String upiId) {
        BankAccount account = accountRepository.findByUpiId(upiId)
                .orElseThrow(() -> new AccountNotFoundException(
                    "No bank account found for upiId: " + upiId));

        return toAccountResponse(account, null);
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    private AccountResponse toAccountResponse(BankAccount account, String message) {
        return AccountResponse.builder()
                .userId(account.getUserId())
                .upiId(account.getUpiId())
                .accountNumber(account.getAccountNumber())
                .bankName(account.getBankName())
                .ifsc(account.getIfsc())
                .balance(account.getBalance())
                .createdAt(account.getCreatedAt())
                .message(message)
                .build();
    }
}
