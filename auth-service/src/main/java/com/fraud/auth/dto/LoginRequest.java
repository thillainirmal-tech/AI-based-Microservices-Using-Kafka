package com.fraud.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * LoginRequest — payload for POST /auth/login
 */
@Data
public class LoginRequest {

    /** Email used as login credential */
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    private String email;

    /** Raw password — validated against BCrypt hash stored in DB */
    @NotBlank(message = "Password is required")
    private String password;
}
