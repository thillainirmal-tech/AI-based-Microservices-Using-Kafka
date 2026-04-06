package com.fraud.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AuthResponse — returned by POST /auth/register and POST /auth/login
 *
 * Clients store the `token` and send it as:
 *   Authorization: Bearer <token>
 * on all subsequent requests through the API Gateway.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    /** JWT Bearer token — expires in 24 hours */
    private String token;

    /** Email of the authenticated user (JWT subject) */
    private String email;

    /** Full name of the user */
    private String name;

    /**
     * Auto-generated UPI ID for this user.
     * Format: sanitised-name@upi (e.g. "johnsmith@upi")
     */
    private String upiId;

    /** Human-readable status message (e.g. "Registration successful") */
    private String message;
}
