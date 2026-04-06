-- =============================================================
-- init-mysql.sql — MySQL initialization script
-- Runs automatically when the MySQL Docker container starts.
-- Creates all required databases for auth-service and bank-service.
--
-- Note: auth_db is already created via MYSQL_DATABASE env var.
-- This script creates the additional bank_db schema.
-- =============================================================

CREATE DATABASE IF NOT EXISTS auth_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS bank_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- Grant root access to both schemas (development only)
-- In production: create dedicated service accounts with minimal privileges:
--   CREATE USER 'auth_user'@'%' IDENTIFIED BY 'auth_password';
--   GRANT SELECT, INSERT, UPDATE, DELETE ON auth_db.* TO 'auth_user'@'%';
--   CREATE USER 'bank_user'@'%' IDENTIFIED BY 'bank_password';
--   GRANT SELECT, INSERT, UPDATE, DELETE ON bank_db.* TO 'bank_user'@'%';

FLUSH PRIVILEGES;
