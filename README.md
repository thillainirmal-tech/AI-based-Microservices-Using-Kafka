# AI-Powered Real-Time Fraud Detection System

> **Event-driven microservices platform** for detecting payment fraud in real time using a 3-layer pipeline: deterministic rule engine → Redis behavioural history → Spring AI (OpenAI GPT).
> Built with Spring Boot 3, Apache Kafka, Redis, MySQL, and Docker Compose.

---

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Quick Start — Docker (Recommended)](#quick-start--docker-recommended)
- [Quick Start — Local Development](#quick-start--local-development)
- [API Reference](#api-reference)
- [Fraud Detection Pipeline](#fraud-detection-pipeline)
- [Environment Variables](#environment-variables)
- [Monitoring & Observability](#monitoring--observability)
- [Security Notes](#security-notes)

---

## Features

- **Real-time fraud detection** via Kafka event streaming — sub-second latency
- **3-layer detection pipeline**: Rules → Redis History → Spring AI (OpenAI GPT)
- **UPI payment simulation** with Bank Service debit/credit and Razorpay integration
- **Email notifications** for every transaction outcome (fraud, safe, failed)
- **Dead Letter Queue (DLQ)** for both deserialization and business-level failures
- **JWT-based authentication** with API Gateway enforcement on all protected routes
- **Rate limiting** on transaction submission (configurable per user)
- **Distributed tracing** via `X-Trace-Id` propagated through all services and Kafka messages
- **Idempotency guards** — fraud analysis and payment are each safe to replay
- **Prometheus + Grafana** metrics out of the box
- **Fully Dockerised** — one command to run all 13 containers

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CLIENT (Postman / Browser)                   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTPS
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   API Gateway  :8085                                │
│          Spring Cloud Gateway + JWT Auth Filter                     │
│    Routes: /auth/** /api/transactions /api/upi/** /api/fraud/**     │
└────┬──────────────┬───────────────────┬────────────────────────────┘
     │              │                   │
     ▼              ▼                   ▼
┌─────────┐  ┌──────────────┐  ┌──────────────────┐
│  Auth   │  │ Transaction  │  │  Fraud Detection │
│ Service │  │ Service      │  │  Service         │
│ :8083   │  │ :8081        │  │  :8082           │
│  MySQL  │  │  Kafka       │  │  Kafka Consumer  │
│  JWT    │  │  Producer    │  │  Redis           │
└─────────┘  └──────┬───────┘  │  Spring AI       │
                    │           └────────┬─────────┘
                    │ topic:             │ topic:
                    │ transactions       │ notifications
                    ▼                   ▼
             ┌────────────┐    ┌──────────────────┐
             │   Kafka    │    │ Notification     │
             │  Broker    │    │ Service  :8086   │
             │  :9092     │    │ Email (SMTP)     │
             └────────────┘    └──────────────────┘
                    │
                    ▼ DLQ: transactions.DLT
             ┌────────────┐
             │  Bank      │
             │  Service   │
             │  :8084     │
             │  MySQL     │
             └────────────┘
```

### Fraud Detection Layers

```
TransactionEvent (Kafka)
        │
        ▼
┌───────────────────────────────────┐
│  LAYER 1 — Rule Engine (<1ms)     │
│  • Amount > threshold  → FRAUD    │
│  • Unknown location    → FRAUD    │
│  • Velocity > limit    → FRAUD    │
└────────────────┬──────────────────┘
                 │ PASS
                 ▼
┌───────────────────────────────────┐
│  LAYER 2 — Redis History          │
│  • Impossible travel   → FRAUD    │
│  • Device change       → REVIEW   │
└────────────────┬──────────────────┘
                 │ PASS / REVIEW
                 ▼
┌───────────────────────────────────┐
│  LAYER 3 — Spring AI (OpenAI)     │
│  • confidence > 0.6    → FRAUD    │
│  • confidence 0.4–0.6  → REVIEW   │
│  • confidence < 0.4    → SAFE     │
└───────────────────────────────────┘
```

---

## Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Java | 17 |
| Framework | Spring Boot | 3.2.3 |
| API Gateway | Spring Cloud Gateway | 2023.0.x |
| Messaging | Apache Kafka (Confluent) | 7.5.0 |
| Kafka Client | Spring Kafka | 3.x |
| AI / LLM | Spring AI + OpenAI GPT | 0.8.x |
| Cache | Redis | 7.2 |
| Database | MySQL | 8.0 |
| Build | Maven | 3.x |
| Containers | Docker + Docker Compose | — |
| Monitoring | Prometheus + Grafana | latest |
| Payments | Razorpay SDK | 1.x |
| Auth | JWT (jjwt) | 0.12.x |

---

## Project Structure

```
fraud-detection-system/
├── common-dto/                     ← Shared Kafka message POJOs
│   └── src/main/java/com/fraud/common/dto/
│       ├── TransactionEvent.java   ← Producer → Consumer contract
│       ├── NotificationEvent.java  ← Fraud → Notification contract
│       └── FraudResult.java        ← Fraud verdict POJO
│
├── api-gateway/                    ← Spring Cloud Gateway (port 8085)
│   └── JwtAuthFilter.java          ← Validates JWT on all protected routes
│
├── auth-service/                   ← JWT auth (port 8083)
│   └── MySQL: auth_db
│
├── bank-service/                   ← Account debit/credit (port 8084)
│   └── MySQL: bank_db
│
├── transaction-service/            ← Kafka producer (port 8081)
│   ├── KafkaProducerConfig.java
│   └── KafkaProducerService.java
│
├── fraud-detection-service/        ← Kafka consumer + AI engine (port 8082)
│   ├── consumer/TransactionConsumer.java
│   ├── config/KafkaConsumerConfig.java          ← DLQ + retry config
│   ├── config/KafkaNotificationProducerConfig.java
│   ├── service/FraudDetectionService.java       ← 3-layer pipeline
│   ├── service/AiFraudAnalysisService.java      ← OpenAI integration
│   └── service/RedisService.java
│
├── notification-service/           ← Email notifications (port 8086)
│   ├── consumer/NotificationConsumer.java
│   └── service/EmailService.java
│
├── docker-compose.yml              ← Full stack (13 containers)
├── prometheus.yml                  ← Metrics scrape config
├── grafana/provisioning/           ← Auto-provisioned Grafana datasource
├── .env.example                    ← Safe template — copy to .env
├── .gitignore
├── .dockerignore
└── pom.xml                         ← Parent Maven POM (multi-module)
```

---

## Quick Start — Docker (Recommended)

> Requires: Docker Desktop, Docker Compose v2

### 1. Clone and configure

```bash
git clone https://github.com/your-username/fraud-detection-system.git
cd fraud-detection-system

# Copy the example env file and fill in your secrets
cp .env.example .env
```

Edit `.env` — at minimum set these values:

```env
JWT_SECRET=your_long_random_secret_here
MYSQL_ROOT_PASSWORD=your_password
MYSQL_PASSWORD=your_password
OPENAI_API_KEY=sk-proj-...
EMAIL_USERNAME=your_email@gmail.com
EMAIL_PASSWORD=your_gmail_app_password
```

### 2. Build all services

```bash
mvn clean package -DskipTests
```

### 3. Start the full stack

```bash
docker-compose up --build -d
```

All 13 containers will start in dependency order. Wait ~60 seconds for MySQL and Kafka to become healthy.

### 4. Verify all services are running

```bash
docker-compose ps
```

Expected output — all services `Up`:

| Container | Port | Status |
|---|---|---|
| fraud-zookeeper | 2181 | Up (healthy) |
| fraud-kafka | 9092, 29092 | Up (healthy) |
| fraud-redis | 6379 | Up (healthy) |
| fraud-mysql | 3307→3306 | Up (healthy) |
| fraud-kafka-ui | 8090 | Up |
| fraud-prometheus | 9090 | Up |
| fraud-grafana | 3000 | Up |
| fraud-bank-service | 8084 | Up |
| fraud-auth-service | 8083 | Up |
| fraud-transaction-service | 8081 | Up |
| fraud-fraud-service | 8082 | Up |
| fraud-notification-service | 8086 | Up |
| fraud-api-gateway | 8085 | Up |

### 5. Test the system

```bash
# Register a user
curl -X POST http://localhost:8085/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@test.com","password":"Test1234!","name":"Test User"}'

# Login and get JWT token
curl -X POST http://localhost:8085/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@test.com","password":"Test1234!"}'

# Submit a transaction (replace TOKEN with your JWT)
curl -X POST http://localhost:8085/api/transactions \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-001",
    "userId": "user@test.com",
    "amount": 500.00,
    "location": "Mumbai",
    "device": "iPhone-14",
    "merchantCategory": "Food"
  }'

# Check fraud result
curl -X GET http://localhost:8085/api/fraud/result/TXN-001 \
  -H "Authorization: Bearer TOKEN"
```

### 6. Tear down

```bash
docker-compose down            # stop containers, keep volumes
docker-compose down -v         # stop and delete all data volumes
```

---

## Quick Start — Local Development

> Requires: Java 17, Maven 3.x, a running local Kafka + Redis + MySQL

### 1. Start infrastructure locally

```bash
# Start only infrastructure (Kafka, Redis, MySQL, Zookeeper)
docker-compose up -d zookeeper kafka redis mysql kafka-ui
```

### 2. Set environment variables

```bash
export SPRING_PROFILES_ACTIVE=local
export JWT_SECRET=your_local_dev_secret
export OPENAI_API_KEY=sk-proj-...
export EMAIL_USERNAME=your_email@gmail.com
export EMAIL_PASSWORD=your_app_password
```

### 3. Build and run each service

In separate terminals (in order):

```bash
# Terminal 1 — Auth Service
cd auth-service && mvn spring-boot:run

# Terminal 2 — Bank Service
cd bank-service && mvn spring-boot:run

# Terminal 3 — Transaction Service
cd transaction-service && mvn spring-boot:run

# Terminal 4 — Fraud Detection Service
cd fraud-detection-service && mvn spring-boot:run

# Terminal 5 — Notification Service
cd notification-service && mvn spring-boot:run

# Terminal 6 — API Gateway (start last)
cd api-gateway && mvn spring-boot:run
```

---

## API Reference

All endpoints except `/auth/**` require a `Authorization: Bearer <token>` header.

### Authentication

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/auth/register` | Register new user + create bank account | Public |
| `POST` | `/auth/login` | Login, returns JWT token | Public |

### Transactions

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/transactions` | Submit transaction for fraud analysis | Required |
| `GET` | `/api/transactions/{id}` | Get transaction status | Required |
| `POST` | `/api/upi/pay` | UPI payment (bank-to-bank via Kafka) | Required |

### Fraud Detection

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/api/fraud/result/{txId}` | Get fraud verdict from Redis | Required |
| `POST` | `/api/fraud/analyze` | Direct fraud analysis (test/debug) | Required |

### Bank Service (Internal)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/bank/accounts` | Create bank account |
| `GET` | `/bank/balance` | Get account balance |
| `POST` | `/bank/debit` | Debit account |
| `POST` | `/bank/credit` | Credit account |

---

## Fraud Detection Pipeline

### Trigger Scenarios

| Scenario | Input | Detection Layer | Verdict |
|---|---|---|---|
| Large transaction | `amount > 10000` | RULE_BASED | FRAUD |
| Unknown location | First tx from new city | RULE_BASED | FRAUD |
| High velocity | `> 10 tx in 24h` | RULE_BASED | FRAUD |
| Impossible travel | 2 cities within 5 min | REDIS_HISTORY | FRAUD |
| Device change | New device for user | REDIS_HISTORY → AI | REVIEW |
| Contextual risk | AI confidence > 0.6 | AI | FRAUD |
| Low risk | AI confidence < 0.4 | AI | SAFE |

### Kafka Topic Flow

```
transaction-service  ──[transactions]──►  fraud-detection-service
                                                    │
                                         ┌──────────┴──────────┐
                                         │                     │
                                    [notifications]      [transactions.DLT]
                                         │                     │
                                         ▼                     ▼
                               notification-service     DLQ consumer
                               (email delivery)         (replay / alert)
```

### DLQ Coverage

Both failure paths are covered:

| Failure Type | Handler | Destination |
|---|---|---|
| Kafka deserialization error | `DeadLetterPublishingRecoverer` (container-level) | `transactions.DLT` |
| Business pipeline exception | Manual `kafkaTemplate.send()` in catch block | `transactions.DLT` |

---

## Environment Variables

All variables are loaded from `.env` via Docker Compose. See `.env.example` for the full list.

| Variable | Required | Description |
|---|---|---|
| `JWT_SECRET` | **Yes** | HS256 signing key (min 32 chars) |
| `MYSQL_ROOT_PASSWORD` | **Yes** | MySQL root password |
| `MYSQL_PASSWORD` | **Yes** | App user password |
| `OPENAI_API_KEY` | **Yes** | OpenAI key for AI fraud layer |
| `EMAIL_USERNAME` | **Yes** | Gmail sender address |
| `EMAIL_PASSWORD` | **Yes** | Gmail App Password (not account password) |
| `RAZORPAY_KEY_ID` | If enabled | Razorpay test/live key ID |
| `RAZORPAY_KEY_SECRET` | If enabled | Razorpay key secret |
| `KAFKA_BOOTSTRAP_SERVERS` | No | Default: `kafka:29092` (Docker) |
| `REDIS_HOST` | No | Default: `redis` (Docker) |
| `FRAUD_HIGH_AMOUNT_THRESHOLD` | No | Default: `10000` |
| `FRAUD_MAX_TX_PER_DAY` | No | Default: `10` |
| `RAZORPAY_ENABLED` | No | Default: `true` |

---

## Monitoring & Observability

| Tool | URL | Description |
|---|---|---|
| **Kafka UI** | http://localhost:8090 | Browse topics, messages, consumer groups, lag |
| **Prometheus** | http://localhost:9090 | Raw metrics scraping |
| **Grafana** | http://localhost:3000 | Dashboards (admin / admin) |
| **API Gateway health** | http://localhost:8085/actuator/health | Gateway liveness |
| **Transaction health** | http://localhost:8081/actuator/health | Transaction service liveness |
| **Fraud health** | http://localhost:8082/actuator/health | Fraud service liveness |
| **Auth health** | http://localhost:8083/actuator/health | Auth service liveness |
| **Bank health** | http://localhost:8084/actuator/health | Bank service liveness |
| **Notification health** | http://localhost:8086/actuator/health | Notification service liveness |

### Custom Metrics (Micrometer)

| Metric | Description |
|---|---|
| `fraud.safe` | Total transactions classified as SAFE |
| `fraud.blocked` | Total transactions classified as FRAUD or REVIEW |

Scrape all services via the Prometheus config at `prometheus.yml`.

---

## Security Notes

- `.env` is **git-ignored** — never committed to source control
- All secrets are injected at runtime via environment variables — no hardcoded credentials in any YAML or Java file
- JWT tokens are validated at the API Gateway before requests reach any service
- MySQL credentials use `MYSQL_USER` / `MYSQL_PASSWORD` (not root across services)
- Email uses Gmail App Password (not your Google account password)
- Docker build context excludes `.env` via `.dockerignore` — secrets cannot leak into Docker image layers

---

## Generating a JWT Secret

```bash
# Option 1 — openssl
openssl rand -base64 48

# Option 2 — Python
python3 -c "import secrets; print(secrets.token_urlsafe(48))"
```

Paste the output as `JWT_SECRET` in your `.env`.

---

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit your changes (`git commit -m 'Add some feature'`)
4. Push to the branch (`git push origin feature/your-feature`)
5. Open a Pull Request

---

## License

This project is licensed under the MIT License.
