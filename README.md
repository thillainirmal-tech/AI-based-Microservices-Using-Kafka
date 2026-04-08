<div align="center">

<img src="https://img.shields.io/badge/SentinelPay-AI%20Fraud%20Detection-22C55E?style=for-the-badge&logo=shield&logoColor=white" alt="SentinelPay"/>

# 🛡️ SentinelPay

### AI-Powered Real-Time Fraud Detection for UPI Payments

*Event-Driven Microservices · Apache Kafka · OpenAI GPT · Spring Boot · React*

---

[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-7.5-231F20?style=flat-square&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![React](https://img.shields.io/badge/React-18-61DAFB?style=flat-square&logo=react&logoColor=black)](https://react.dev/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square&logo=docker&logoColor=white)](https://docs.docker.com/compose/)
[![Redis](https://img.shields.io/badge/Redis-7.2-DC382D?style=flat-square&logo=redis&logoColor=white)](https://redis.io/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?style=flat-square&logo=mysql&logoColor=white)](https://www.mysql.com/)
[![OpenAI](https://img.shields.io/badge/OpenAI-GPT--4-412991?style=flat-square&logo=openai&logoColor=white)](https://openai.com/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](LICENSE)

</div>

---

## 🚀 Overview

**SentinelPay** is a production-grade, event-driven microservices platform that analyses every UPI payment transaction through a **3-layer AI fraud detection pipeline** — before a single rupee moves.

Built to mirror real-world fintech systems, it combines deterministic rule evaluation, Redis-backed behavioural pattern analysis, and OpenAI GPT contextual reasoning into a unified fraud verdict engine. The entire system runs with a single command and ships with full observability via Prometheus and Grafana.

```
User submits UPI payment
       ↓
  API Gateway (JWT auth)
       ↓
  Transaction Service → Kafka → Fraud Detection Service
                                       ↓
                            Layer 1: Rule Engine     (<1ms)
                            Layer 2: Redis History   (behavioural)
                            Layer 3: OpenAI GPT      (AI scoring)
                                       ↓
                              SAFE | REVIEW | FRAUD
                                       ↓
                         Bank Transfer OR Block + Email Alert
```

---

## 🧠 Problem Statement

UPI fraud in India crossed **₹2,000 crore** in reported cases in 2023. Existing payment systems often apply fraud checks *after* money moves — resulting in permanent loss for victims. The core challenges are:

- Fraud must be detected **in milliseconds**, not minutes
- Rules-only systems are brittle — fraudsters adapt quickly
- AI-only systems are too slow and expensive for every transaction
- Users need **real-time feedback** without blocking payment UX

---

## 💡 Solution

SentinelPay solves this with a **short-circuit 3-layer pipeline**:

| Layer | Technology | Speed | What it catches |
|-------|-----------|-------|----------------|
| Rule Engine | Java (pure logic) | < 1ms | Amount spikes, unknown locations, velocity abuse |
| Behavioural Analysis | Redis time-series | < 5ms | Impossible travel (2 cities in 5 min), device switching |
| AI Scoring | OpenAI GPT-4 | 200–800ms | Subtle patterns, new fraud vectors, contextual anomalies |

Each layer **short-circuits** on a FRAUD verdict — the AI only runs when rules pass. This keeps average latency low while maintaining AI-grade accuracy for borderline transactions.

Kafka decouples payment submission from analysis — users see `PENDING` instantly and poll for the result, enabling fast UX without sacrificing thoroughness.

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          CLIENT (Browser)                                   │
│                     React + Material UI (Port 3001)                         │
└─────────────────────────────┬───────────────────────────────────────────────┘
                              │ HTTP (Nginx reverse proxy)
┌─────────────────────────────▼───────────────────────────────────────────────┐
│                         API GATEWAY                                         │
│                   Spring Cloud Gateway (Port 8085)                          │
│         JWT Validation · Route Matching · X-User-Email injection            │
│         X-Trace-Id propagation · CORS · Rate limiting                       │
└──────┬─────────────────┬──────────────────┬──────────────────┬──────────────┘
       │                 │                  │                  │
┌──────▼──────┐  ┌───────▼───────┐  ┌──────▼──────┐  ┌───────▼───────┐
│ AUTH SERVICE│  │  TRANSACTION  │  │  FRAUD DET. │  │ BANK SERVICE  │
│  Port 8083  │  │   SERVICE     │  │   SERVICE   │  │   Port 8084   │
│             │  │   Port 8081   │  │  Port 8082  │  │               │
│ Register    │  │               │  │             │  │ Balances      │
│ Login       │  │ POST /upi/pay │  │ 3-Layer AI  │  │ Debit/Credit  │
│ JWT issue   │  │ → Kafka pub   │  │ Redis cache │  │ UPI lookup    │
│             │  │ Razorpay ord. │  │ OpenAI GPT  │  │ Compensation  │
└──────┬──────┘  └───────┬───────┘  └──────┬──────┘  └───────────────┘
       │                 │                  │
       │         ┌───────▼──────────────────▼──────┐
       │         │           APACHE KAFKA           │
       │         │  Topic: transactions (3 parts)   │
       │         │  Topic: notifications            │
       │         │  Topic: transactions.DLT         │
       │         └─────────────────────────────────┘
       │                                   │
       │                          ┌────────▼────────┐
       │                          │ NOTIFICATION SVC │
       │                          │   Port 8086      │
       │                          │ Email via SMTP   │
       │                          └─────────────────┘
       │
┌──────▼──────────────────────────────────────────────────────────────────────┐
│                           INFRASTRUCTURE                                    │
│  MySQL 8 (auth_db, bank_db)  ·  Redis 7 (fraud cache, rate limits)         │
│  Zookeeper · Prometheus · Grafana · Kafka UI                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Service Communication

| From | To | Protocol | Purpose |
|------|----|----------|---------|
| Transaction Service | Kafka | Async publish | Send transaction event for fraud analysis |
| Fraud Detection | Kafka | Async consume | Receive transaction, run pipeline |
| Fraud Detection | Kafka | Async publish | Send notification event on FRAUD verdict |
| Notification Service | Kafka | Async consume | Send fraud alert email |
| Auth Service | Bank Service | REST (sync) | Create bank account on user registration |
| Transaction Service | Bank Service | REST (sync) | Validate payee UPI, debit/credit funds |
| Fraud Detection | Bank Service | REST (sync) | Resolve payee UPI → email for payment |
| Frontend | API Gateway | HTTP/Nginx | All API calls via reverse proxy |

---

## ⚙️ Tech Stack

### Backend
| Technology | Version | Role |
|-----------|---------|------|
| Java | 17 | Primary language |
| Spring Boot | 3.x | Microservice framework |
| Spring Cloud Gateway | 4.x | API Gateway, JWT filter, routing |
| Spring AI | Latest | OpenAI GPT integration |
| Spring Security | 6.x | Auth service security config |
| Apache Kafka | 7.5 (Confluent) | Async event streaming |
| Redis | 7.2 | Fraud result cache, user history |
| MySQL | 8.0 | User accounts, bank accounts |
| Razorpay SDK | Latest | Real payment gateway integration |
| Lombok | Latest | Boilerplate reduction |
| JJWT | Latest | JWT generation and validation |

### Frontend
| Technology | Version | Role |
|-----------|---------|------|
| React | 18 | SPA framework |
| Material UI | 5.x | Component library |
| Axios | Latest | HTTP client with interceptors |
| react-hook-form | Latest | Form validation |
| DOMPurify | Latest | XSS sanitisation |
| react-hot-toast | Latest | Notification toasts |
| recharts | Latest | Analytics charts |

### DevOps & Observability
| Technology | Role |
|-----------|------|
| Docker + Docker Compose | Container orchestration |
| Nginx | Frontend serving + reverse proxy |
| Prometheus | Metrics scraping (Spring Actuator) |
| Grafana | Dashboards and alerting |
| Kafka UI | Topic and message inspection |
| Spring Actuator | Health, metrics, gateway endpoints |

---

## 📂 Project Structure

```
fraud-detection-system/
│
├── api-gateway/              # Spring Cloud Gateway — routing, JWT auth, CORS
│   └── src/main/java/com/fraud/gateway/
│       ├── config/           # GatewayConfig (CORS), JwtConfig
│       └── filter/           # JwtAuthFilter (validates JWT, injects X-User-Email)
│
├── auth-service/             # User registration, login, JWT issuance
│   └── src/main/java/com/fraud/auth/
│       ├── controller/       # AuthController (/auth/login, /auth/register)
│       ├── service/          # AuthService, JwtService
│       ├── entity/           # User (JPA entity, MySQL)
│       └── client/           # BankServiceClient (creates bank account on register)
│
├── transaction-service/      # UPI payment submission, Kafka producer
│   └── src/main/java/com/fraud/transaction/
│       ├── controller/       # UpiController (/api/upi/pay), TransactionController
│       ├── service/          # UpiPaymentService, Kafka producer
│       └── client/           # BankServiceValidationClient, FraudServiceClient
│
├── fraud-detection-service/  # Core fraud engine (3-layer pipeline)
│   └── src/main/java/com/fraud/detection/
│       ├── consumer/         # TransactionConsumer (Kafka listener)
│       ├── service/          # FraudDetectionService (orchestrator)
│       │                     # AiFraudAnalysisService (OpenAI GPT)
│       │                     # RedisService (history + result cache)
│       ├── payment/          # PaymentProcessorService, RazorpayService
│       ├── producer/         # NotificationProducer
│       └── controller/       # FraudController (/api/fraud/result/{id})
│
├── bank-service/             # Bank account simulation
│   └── src/main/java/com/fraud/bank/
│       ├── controller/       # BankController (balance, debit, credit, UPI lookup)
│       ├── service/          # BankService (pessimistic locking, idempotency)
│       └── entity/           # BankAccount, BankTransaction (JPA entities)
│
├── notification-service/     # Email alerts for fraud events
│   └── src/main/java/com/fraud/notification/
│       ├── consumer/         # NotificationConsumer (Kafka listener)
│       └── service/          # EmailService, EmailTemplateBuilder
│
├── common-dto/               # Shared Kafka message DTOs
│   └── src/main/java/com/fraud/common/dto/
│       ├── TransactionEvent.java   # Kafka message: payment → fraud detection
│       ├── FraudResult.java        # Kafka result + Redis cache DTO
│       └── NotificationEvent.java  # Kafka message: fraud → notification
│
├── sentinelpay-frontend/     # React SPA
│   ├── src/
│   │   ├── api/              # authApi, fraudApi, transactionApi, bankApi, axiosConfig
│   │   ├── context/          # AuthContext, AppStateContext, ThemeContext
│   │   ├── pages/            # Dashboard, Login, Register, Transactions, FraudAlerts
│   │   ├── components/       # Fraud visualisation, charts, layout
│   │   └── hooks/            # useSSE (Server-Sent Events with polling fallback)
│   ├── nginx.conf            # Nginx: SPA serving + reverse proxy to API Gateway
│   └── Dockerfile            # Multi-stage: Node build → Nginx serve
│
├── docker-compose.yml        # Full stack orchestration (13 containers)
├── prometheus.yml            # Prometheus scrape config
├── grafana/                  # Grafana dashboard provisioning
└── .env                      # Environment variables (copy .env.example)
```

---

## 🔄 Data Flow

### UPI Payment Lifecycle

```
1. USER submits payment on React Dashboard
   POST /api/upi/pay  { payeeUpiId, amount, device, location, merchantCategory }

2. API GATEWAY
   ├── Validates JWT (extracts email)
   ├── Injects X-User-Email header (identity source of truth)
   ├── Generates X-Trace-Id (UUID) for distributed tracing
   └── Routes to transaction-service

3. TRANSACTION SERVICE
   ├── Validates request body (Jakarta Bean Validation)
   ├── Looks up payee UPI → bank account (bank-service)
   ├── Publishes TransactionEvent to Kafka topic "transactions"
   └── Returns HTTP 202 { transactionId, status: "PENDING" }

4. KAFKA delivers event to FRAUD DETECTION SERVICE

5. FRAUD DETECTION SERVICE — 3-layer pipeline:
   │
   ├── Layer 1: Rule Engine
   │   ├── Amount > ₹10,000 threshold? → FRAUD
   │   ├── Unknown location for this user? → FRAUD
   │   └── > 10 transactions today? → FRAUD (velocity)
   │
   ├── Layer 2: Redis History Analyser (if rules pass)
   │   ├── Two different cities within 5 minutes? → FRAUD (impossible travel)
   │   └── Device changed since last transaction? → REVIEW (escalate to AI)
   │
   └── Layer 3: OpenAI GPT (if history passes or REVIEW from L2)
       ├── confidence > 0.6  → FRAUD
       ├── confidence 0.4–0.6 → REVIEW (human analyst queue)
       └── confidence < 0.4  → SAFE

6. FRAUD RESULT stored in Redis (TTL: 72h)
   Key: "fraud:result:{transactionId}"

7. If SAFE → PAYMENT PROCESSING
   ├── Debit payer's bank account (bank-service)
   ├── Credit payee's bank account (bank-service)
   └── Update PaymentRecord in Redis (status: SUCCESS)

   If FRAUD → BLOCK + NOTIFY
   ├── No money moves
   ├── Publish NotificationEvent to Kafka "notifications" topic
   └── Email sent to user (notification-service → SMTP)

8. FRONTEND polls GET /api/fraud/result/{transactionId}
   ├── 202 → still pending (retry after retryAfterSeconds hint)
   └── 200 → verdict ready (render SAFE / REVIEW / FRAUD UI)
```

---

## 🔐 Security Features

### Authentication & Authorisation
- **JWT-based auth** issued by auth-service on login/register
- **Stateless validation** at the API Gateway — every request is independently verified
- **X-User-Email header injection** — payer identity always comes from the validated JWT, never from the request body (prevents identity spoofing)
- Gateway bypass detection — requests missing `X-User-Email` return 401 with a security log warning

### Data Protection
- **BCrypt password hashing** — passwords are never stored in plaintext
- **DOMPurify XSS sanitisation** on all user inputs before rendering or API calls (frontend)
- **Input validation** via Jakarta Bean Validation on all controller endpoints
- **No sensitive data in URLs** — all identity transmitted via secure headers

### Distributed Security
- **X-Trace-Id propagation** across all services and Kafka messages — enables end-to-end audit trails in case of suspicious activity
- **Rate limiting** on UPI payment submissions (configurable: max requests per time window)
- **Internal service isolation** — bank debit/credit/refund endpoints are not exposed through the API Gateway (only balance and UPI lookup are externally accessible)

### Frontend Security
- **AbortController** cancels in-flight requests on component unmount — prevents data leakage from stale responses
- **Refresh token rotation** stored in `sessionStorage` (cleared on tab close)
- **GET request deduplication** — prevents duplicate concurrent reads via in-flight AbortController map

---

## 🧪 API Endpoints

### Auth Service (`/auth/**` — public, no JWT required)

| Method | Endpoint | Description | Request Body |
|--------|----------|-------------|-------------|
| `POST` | `/auth/register` | Register new user, auto-create bank account | `{ name, email, password }` |
| `POST` | `/auth/login` | Login, receive JWT | `{ email, password }` |
| `GET` | `/auth/health` | Service liveness check | — |

**Register response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "email": "john@example.com",
  "name": "John Smith",
  "upiId": "johnsmith@upi",
  "message": "Registration successful"
}
```

### Transaction Service (`/api/**` — JWT required)

| Method | Endpoint | Description | Request Body |
|--------|----------|-------------|-------------|
| `POST` | `/api/upi/pay` | Submit UPI payment (async, returns 202) | `{ payeeUpiId, amount, device, location, merchantCategory, paymentMode }` |
| `GET` | `/api/transactions/{id}` | Get combined payment + fraud status | — |

**UPI pay response (202 Accepted):**
```json
{
  "transactionId": "3f8a2b1c-...",
  "status": "PENDING",
  "message": "Poll /api/transactions/{id} for result",
  "payerEmail": "john@example.com",
  "payeeUpiId": "alice@upi",
  "amount": 500.00
}
```

### Fraud Detection Service (`/api/fraud/**` — JWT required)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/fraud/result/{txId}` | Poll fraud verdict (200 = ready, 202 = pending) |
| `GET` | `/api/fraud/payment/{txId}` | Payment lifecycle status |
| `GET` | `/api/fraud/history/{userId}` | User transaction history (debug) |
| `DELETE` | `/api/fraud/result/{txId}` | Clear result for re-analysis |
| `GET` | `/api/fraud/health` | Service liveness check |

**Fraud result response (200):**
```json
{
  "transactionId": "3f8a2b1c-...",
  "userId": "john@example.com",
  "status": "FRAUD",
  "reason": "Transaction amount 25000 INR exceeds high-risk threshold of 10000 INR",
  "confidenceScore": 0.95,
  "detectionLayer": "RULE_BASED",
  "analyzedAt": "2025-04-08 14:23:01"
}
```

**Fraud result response (202 — still processing):**
```json
{
  "status": "PENDING",
  "message": "Fraud analysis in progress",
  "retryAfterSeconds": 3
}
```

### Bank Service (`/bank/**` — JWT required)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/bank/balance` | Get authenticated user's balance |
| `GET` | `/bank/account/by-upi/{upiId}` | Look up account by UPI ID (payee resolution) |

---

## 🐳 Docker Setup

> **One command to run the entire platform — 13 containers.**

### Prerequisites

- Docker Desktop 4.x+ (or Docker Engine + Compose v2)
- At least 6GB RAM allocated to Docker
- Ports free: 3001, 8081–8086, 8085, 8090, 9090, 3000, 6379, 3307, 9092, 2181

### 1. Clone the repository

```bash
git clone https://github.com/thillainirmal-tech/SentinelPay.git
cd SentinelPay/fraud-detection-system
```

### 2. Configure environment

```bash
cp .env.example .env
```

Edit `.env` and fill in your values:

```env
# Database
MYSQL_ROOT_PASSWORD=your_secure_password
MYSQL_USER=root
MYSQL_PASSWORD=your_secure_password

# JWT (generate a strong 256-bit secret)
JWT_SECRET=your-256-bit-secret-key-here
JWT_EXPIRATION_MS=86400000

# OpenAI (required for Layer 3 AI fraud detection)
OPENAI_API_KEY=sk-proj-...

# Razorpay (optional — set RAZORPAY_ENABLED=false to use bank simulation only)
RAZORPAY_ENABLED=false
RAZORPAY_KEY_ID=rzp_test_...
RAZORPAY_KEY_SECRET=...

# Email notifications (Gmail App Password recommended)
EMAIL_USERNAME=your@gmail.com
EMAIL_PASSWORD=your-app-password

# Fraud thresholds (tunable without code changes)
FRAUD_HIGH_AMOUNT_THRESHOLD=10000
FRAUD_MAX_TX_PER_DAY=10
FRAUD_IMPOSSIBLE_TRAVEL_MIN=5
```

### 3. Build and start

```bash
docker-compose up -d --build
```

> First build takes 5–8 minutes (downloads base images, compiles all services). Subsequent builds are faster due to Docker layer caching.

### 4. Verify all services are healthy

```bash
docker-compose ps
```

All services should show `Up (healthy)` or `Up`. Wait ~60 seconds for all health checks to pass after first start.

### Access the platform

| Service | URL | Credentials |
|---------|-----|-------------|
| 🖥️ Frontend | http://localhost:3001 | Register a new account |
| 📊 Grafana | http://localhost:3000 | admin / admin |
| 🔍 Kafka UI | http://localhost:8090 | — |
| 📈 Prometheus | http://localhost:9090 | — |
| 🗄️ API Gateway | http://localhost:8085 | Via frontend only |

### Stop the stack

```bash
docker-compose down           # Stop containers (data preserved)
docker-compose down -v        # Stop and delete all volumes (fresh start)
```

---

## ▶️ Local Development Setup

### Prerequisites

- Java 17+
- Node.js 18+
- Maven 3.8+
- Docker (for infrastructure services)

### 1. Start infrastructure only

```bash
docker-compose up -d zookeeper kafka redis mysql kafka-ui prometheus grafana
```

Wait for MySQL and Kafka to be healthy:
```bash
docker-compose ps
```

### 2. Start backend services

Open 6 terminals (or use your IDE's multi-run config). In each:

```bash
# Terminal 1 — Bank Service
cd fraud-detection-system/bank-service
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 2 — Auth Service
cd fraud-detection-system/auth-service
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 3 — Transaction Service
cd fraud-detection-system/transaction-service
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 4 — Fraud Detection Service
cd fraud-detection-system/fraud-detection-service
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 5 — Notification Service
cd fraud-detection-system/notification-service
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 6 — API Gateway
cd fraud-detection-system/api-gateway
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 3. Start frontend

```bash
cd fraud-detection-system/sentinelpay-frontend
npm install
npm start
```

Frontend runs at **http://localhost:3000** in development mode (direct to API Gateway at port 8085).

---

## 📸 Screenshots

> *Screenshots and demo GIF coming soon*

| Screen | Description |
|--------|-------------|
| 🏠 Dashboard | Payment form, balance card, activity feed |
| 📊 Analytics | Transaction volume, fraud rate charts |
| 🚨 Fraud Alerts | Real-time fraud detection results with confidence scores |
| 📋 Transactions | Full payment history with verdict badges |
| 👤 Profile | User info, UPI ID, account details |
| 📈 Grafana | Service metrics, Kafka lag, Redis hit rate |

---

## 📈 Future Enhancements

| Feature | Priority | Description |
|---------|----------|-------------|
| Webhook notifications | High | Real-time push to registered URLs on fraud events |
| ML model fine-tuning | High | Train on historical transaction data instead of pure GPT |
| Multi-factor auth (OTP) | High | TOTP / SMS OTP for high-value transactions |
| Admin fraud dashboard | Medium | Real-time fraud queue with REVIEW case management |
| Razorpay webhooks | Medium | Reconcile Razorpay payment status with internal records |
| Kubernetes deployment | Medium | Helm charts for production-grade orchestration |
| Service discovery | Medium | Spring Cloud Eureka for dynamic service registration |
| Circuit breakers | Medium | Resilience4j for fault-tolerant inter-service calls |
| Audit log service | Low | Immutable event store for regulatory compliance |
| A/B fraud rule testing | Low | Shadow-mode rule evaluation without blocking transactions |

---

## 🤝 Contribution Guide

Contributions are welcome! Please follow these steps:

1. **Fork** the repository
2. **Create a feature branch**: `git checkout -b feature/your-feature-name`
3. **Follow code style**: Java code uses the existing package structure; React code follows the existing hook/context patterns
4. **Test your changes**: Ensure existing API contracts are preserved
5. **Commit with clear messages**: `feat: add impossible travel detection for IPv6`
6. **Open a Pull Request** with a description of what changed and why

### Areas open for contribution

- Additional fraud detection rules (Layer 1)
- Grafana dashboard templates
- Unit and integration tests
- Documentation improvements
- Frontend UI enhancements

---

## 📜 License

This project is licensed under the **Apache License 2.0** — see the [LICENSE](LICENSE) file for details.

```
Copyright 2026 THillainirmal

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

---

<div align="center">

**Built with ❤️ by [THillainirmal](https://github.com/thillainirmal-tech)**

*If this project helped you, please consider giving it a ⭐*

</div>
