package com.fraud.auth.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * User — JPA entity persisted in MySQL.
 *
 * Represents a registered user of the UPI payment system.
 * Password is stored as BCrypt hash — never plaintext.
 * UPI ID is auto-generated at registration: {name}@upi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = "email", name = "uk_users_email"),
           @UniqueConstraint(columnNames = "upi_id", name = "uk_users_upi_id")
       })
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Full name of the user */
    @Column(nullable = false)
    private String name;

    /** Email — used as login credential and JWT subject */
    @Column(nullable = false, unique = true)
    private String email;

    /** BCrypt-hashed password — never stored as plaintext */
    @Column(nullable = false)
    private String password;

    /**
     * UPI ID — auto-generated at registration.
     * Format: sanitised-name@upi (e.g. "johnsmith@upi")
     * Must be unique across all users.
     */
    @Column(name = "upi_id", nullable = false, unique = true)
    private String upiId;

    /** When this account was created */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
