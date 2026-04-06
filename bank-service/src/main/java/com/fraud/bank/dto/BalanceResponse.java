package com.fraud.bank.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * BalanceResponse — lightweight response for GET /bank/balance
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceResponse {

    private String     userId;
    private String     upiId;
    private BigDecimal balance;
    private String     currency;
}
