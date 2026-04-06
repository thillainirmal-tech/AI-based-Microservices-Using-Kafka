# AI-Based Real-Time Fraud Detection System
### Kafka-Driven Microservices | Spring Boot 3 | Spring AI | Redis

---

## Architecture

```
Client (Postman / React)
         │
         ▼
┌─────────────────────┐
│    API Gateway      │  :8080  Spring Cloud Gateway
└─────────────────────┘
         │
         ▼
┌─────────────────────┐
│ Transaction Service │  :8081  REST API + Kafka Producer
└─────────────────────┘
         │ Kafka topic: "transactions"
         ▼
┌──────────────────────────────────────────────────────┐
│              Fraud Detection Service  :8082           │
│                                                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │
│  │ Rule Engine │→ │  Redis      │→ │  Spring AI  │  │
│  │ (instant)   │  │  (history)  │  │  (OpenAI)   │  │
│  └─────────────┘  └─────────────┘  └─────────────┘  │
│           ↓               ↓               ↓          │
│                   SAFE / FRAUD                       │
└──────────────────────────────────────────────────────┘
```

---

## Project Structure

```
fraud-detection-system/
├── pom.xml                          ← Parent Maven POM (multi-module)
├── docker-compose.yml               ← Kafka + Zookeeper + Redis + Kafka UI
├── postman-collection.json          ← Ready-to-import API tests
│
├── common-dto/                      ← Shared DTOs (used by all services)
│   └── src/main/java/com/fraud/common/dto/
│       ├── TransactionEvent.java    ← Kafka message POJO
│       └── FraudResult.java         ← Fraud verdict POJO
│
├── api-gateway/                     ← Spring Cloud Gateway
│   └── src/main/java/com/fraud/gateway/
│       ├── ApiGatewayApplication.java
│       └── config/GatewayConfig.java
│
├── transaction-service/             ← Kafka Producer
│   └── src/main/java/com/fraud/transaction/
│       ├── TransactionServiceApplication.java
│       ├── controller/TransactionController.java
│       ├── service/TransactionService.java
│       ├── service/KafkaProducerService.java
│       ├── config/KafkaProducerConfig.java
│       ├── dto/TransactionRequest.java
│       ├── dto/TransactionResponse.java
│       └── exception/GlobalExceptionHandler.java
│
└── fraud-detection-service/         ← Kafka Consumer + AI Engine
    └── src/main/java/com/fraud/detection/
        ├── FraudDetectionApplication.java
        ├── consumer/TransactionConsumer.java    ← @KafkaListener
        ├── service/FraudDetectionService.java   ← 3-layer detection
        ├── service/RedisService.java            ← User history cache
        ├── service/AiFraudAnalysisService.java  ← Spring AI / OpenAI
        ├── controller/FraudController.java
        ├── config/KafkaConsumerConfig.java
        ├── config/RedisConfig.java
        └── model/UserTransactionHistory.java
```

---

## Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 17 | Language |
| Spring Boot | 3.2.3 | Framework |
| Spring Cloud Gateway | 2023.0.0 | API Gateway / routing |
| Apache Kafka | 7.5.0 (Confluent) | Message broker |
| Spring Kafka | 3.x | Kafka producer/consumer |
| Spring AI | 0.8.1 | OpenAI GPT integration |
| Redis | 7.2 | User behaviour cache |
| Spring Data Redis | 3.x | Redis operations |
| Lombok | 1.18.30 | Boilerplate reduction |
| Maven | 3.x | Build tool |

---

## Quick Start

### Step 1: Start Infrastructure

```bash
cd fraud-detection-system
docker-compose up -d
```

Verify services:
```
✓ Zookeeper    → localhost:2181
✓ Kafka        → localhost:9092
✓ Redis        → localhost:6379
✓ Kafka UI     → http://localhost:8090
```

### Step 2: Set OpenAI API Key

```bash
export OPENAI_API_KEY=sk-your-key-here
```

Or edit `fraud-detection-service/src/main/resources/application.yml`:
```yaml
spring.ai.openai.api-key: sk-your-key-here
```

### Step 3: Build the Project

```bash
mvn clean install -DskipTests
```

### Step 4: Start Services (3 terminals)

**Terminal 1 — API Gateway**
```bash
cd api-gateway
mvn spring-boot:run
# Starts on port 8080
```

**Terminal 2 — Transaction Service**
```bash
cd transaction-service
mvn spring-boot:run
# Starts on port 8081
```

**Terminal 3 — Fraud Detection Service**
```bash
cd fraud-detection-service
mvn spring-boot:run
# Starts on port 8082
```

---

## API Reference

### Submit a Transaction
```
POST http://localhost:8080/api/transactions
Content-Type: application/json

{
  "transactionId": "TXN-001",
  "userId":        "USR-42",
  "amount":        500.00,
  "location":      "Mumbai",
  "device":        "iPhone-14",
  "merchantCategory": "Food"
}
```

**Response (202 Accepted):**
```json
{
  "transactionId": "TXN-001",
  "message": "Transaction accepted and queued for fraud analysis",
  "statusCode": 202
}
```

### Fraud Detection Flow

| Scenario | Trigger | Layer | Verdict |
|---|---|---|---|
| amount > 10,000 | `"amount": 25000` | RULE_BASED | FRAUD |
| Unknown location | new city for user | RULE_BASED | FRAUD |
| > 10 tx in 24h | rapid submissions | RULE_BASED | FRAUD |
| Impossible travel | 2 cities in 5min | REDIS_HISTORY | FRAUD |
| Contextual anomaly | GPT analysis | AI | SAFE/FRAUD |

---

## Fraud Detection Layers

### Layer 1: Rule Engine (Instant)
- **Amount > 10,000 INR** → immediate FRAUD
- **Unknown location** → FRAUD (checked against Redis history)
- **> 10 transactions in 24h** → FRAUD

### Layer 2: Redis History Analysis
- **Impossible travel** — 2 different locations within 5 minutes → FRAUD
- Device change detection (escalates to AI)

### Layer 3: Spring AI (OpenAI GPT)
- Sends structured prompt with full transaction context + user history
- GPT returns: `VERDICT`, `CONFIDENCE`, `REASON`
- Model: `gpt-3.5-turbo` (configurable to `gpt-4o`)

---

## Configuration

### Environment Variables (Docker / Production)

| Variable | Description | Default |
|---|---|---|
| `OPENAI_API_KEY` | OpenAI API key | (required) |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker | `localhost:9092` |
| `REDIS_HOST` | Redis hostname | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |

---

## Monitoring

- **Kafka UI:** http://localhost:8090 — View topics, messages, consumer groups
- **Actuator Health:**
  - Gateway: http://localhost:8080/actuator/health
  - Transaction: http://localhost:8081/actuator/health
  - Fraud Detection: http://localhost:8082/actuator/health

---

## Testing with Postman

Import `postman-collection.json` into Postman.

Test scenarios included:
1. ✅ Safe normal transaction
2. 🚨 High amount fraud
3. 🚨 Unknown location fraud
4. 🚨 High frequency fraud
5. 🔍 Get user Redis history
6. ❌ Validation error (400)
"# AI-Driven-Fraud-Detection-Framework" 
