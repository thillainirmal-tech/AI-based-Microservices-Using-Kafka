package com.fraud.bank.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * CreateAccountRequest — payload for POST /bank/account/create
 *
 * Called by auth-service during user registration.
 * Bank service creates the account with a default balance of 10,000.
 */
@Data
public class CreateAccountRequest {

    /** Email of the user (userId) — must match auth-service User.email */
    @NotBlank(message = "userId is required")
    @Email(message = "userId must be a valid email")
    private String userId;

    /** Auto-generated UPI ID from auth-service (e.g. "johnsmith@upi") */
    @NotBlank(message = "upiId is required")
    private String upiId;
}
