package com.fraud.bank.repository;

import com.fraud.bank.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    /** Look up account by owner email (primary key for money ops) */
    Optional<BankAccount> findByUserId(String userId);

    /** Look up account by UPI ID — used for payee resolution */
    Optional<BankAccount> findByUpiId(String upiId);

    boolean existsByUserId(String userId);

    boolean existsByUpiId(String upiId);

    /**
     * Pessimistic write lock on the account row — used during debit to
     * prevent concurrent overdraft. Complements the @Version optimistic lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM BankAccount a WHERE a.userId = :userId")
    Optional<BankAccount> findByUserIdForUpdate(@Param("userId") String userId);
}
