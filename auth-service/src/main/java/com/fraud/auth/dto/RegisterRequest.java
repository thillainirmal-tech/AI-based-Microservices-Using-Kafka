package com.fraud.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * RegisterRequest — payload for POST /auth/register
 *
 * Validated by Spring's @Valid annotation in the controller.
 * UPI ID is auto-generated server-side from the name — not accepted from client.
 */
@Data
public class RegisterRequest {

    /** Full name of the user */
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    private String name;

    /** Email — used as login credential and JWT subject */
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    private String email;

    /**
     * Password rules:
     *   - Minimum 6 characters
     *   - Must contain at least one digit (0-9)
     *
     * Will be BCrypt-hashed before storage — never stored as plaintext.
     *
     * Regex: ^(?=.*[0-9]).{6,}$
     *   (?=.*[0-9])  — at least one digit anywhere in the string
     *   .{6,}        — total length ≥ 6
     */
    @NotBlank(message = "Password is required")
    @Pattern(
        regexp  = "^(?=.*[0-9]).{6,}$",
        message = "Password must be at least 6 characters and contain at least one number"
    )
    private String password;
}
