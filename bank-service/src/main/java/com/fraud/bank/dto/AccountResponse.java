package com.fraud.bank.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AccountResponse — returned by GET /bank/balance and POST /bank/account/create
 *
 * Exposes non-sensitive account details. Password, version, and internal IDs
 * are deliberately excluded.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountResponse {

    private String      userId;
    private String      upiId;
    private String      accountNumber;
    private String      bankName;
    private String      ifsc;
    private BigDecimal  balance;
    private LocalDateTime createdAt;
    private String      message;
}
