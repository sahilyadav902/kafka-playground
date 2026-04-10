# Kafka Playground — A to Z

A complete Kafka learning environment with a 3-broker cluster, shell command reference, Spring Boot app, and Postman collection.

---

## Quick Start

### 1. Start the Kafka Cluster
```bash
docker-compose up -d
```
- Kafka brokers: `localhost:9092`, `localhost:9093`, `localhost:9094`
- Kafka UI: http://localhost:8090 (visual cluster explorer)
- Zookeeper: `localhost:2181`

### 2. Run Shell Command Scenarios
```bash
# Full command reference (topics, producers, consumers, offsets, replication)
bash kafka-commands/01-complete-reference.sh

# System design scenarios (DLQ, retries, ordering, compaction, DR)
bash kafka-commands/02-system-design-scenarios.sh

# Transactions, exactly-once semantics, tombstones
bash kafka-commands/03-transactions-and-tombstones.sh

# Advanced: interceptors, compression, pause/resume, quotas, lag alerting
bash kafka-commands/04-advanced-producer-consumer.sh
```

### 3. Start the Spring Boot App
```bash
cd spring-boot-app
./gradlew bootRun
```
App runs on http://localhost:8081

### 4. Import Postman Collection
Import `postman/kafka-playground.postman_collection.json` into Postman and run requests.

### 5. Run Tests
```bash
cd spring-boot-app
./gradlew test
```

---

## Project Structure

```
kafka-playground/
├── docker-compose.yml
├── kafka-commands/
│   ├── 01-complete-reference.sh          # Every Kafka CLI command A-Z
│   ├── 02-system-design-scenarios.sh     # 10 real-world system design scenarios
│   ├── 03-transactions-and-tombstones.sh # Exactly-once, atomic writes, GDPR tombstones
│   └── 04-advanced-producer-consumer.sh  # Interceptors, compression, pause/resume, quotas
├── spring-boot-app/
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/
│       ├── main/java/com/kafkaplayground/
│       │   ├── KafkaPlaygroundApplication.java
│       │   ├── config/
│       │   │   ├── KafkaTopicConfig.java       # All topic definitions
│       │   │   ├── KafkaProducerConfig.java    # Transactional factory + ProducerAuditInterceptor
│       │   │   └── KafkaConsumerConfig.java    # Manual ack + batch listener factories
│       │   ├── model/Order.java
│       │   ├── producer/KafkaProducerService.java  # All producer patterns
│       │   └── consumer/KafkaConsumerService.java  # All consumer patterns + pause/resume
│       │       └── controller/KafkaController.java # REST APIs
│       ├── main/resources/application.properties
│       └── test/java/com/kafkaplayground/
│           ├── KafkaProducerServiceTest.java   # Unit tests (Mockito)
│           ├── KafkaControllerTest.java        # Controller tests (MockMvc)
│           └── KafkaIntegrationTest.java       # Integration tests (EmbeddedKafka)
└── postman/
    └── kafka-playground.postman_collection.json
```

---

## Kafka Concepts Covered

| Concept | Where |
|---|---|
| Topics, Partitions, Replicas | `01-complete-reference.sh` §1, `KafkaTopicConfig.java` |
| Keyed messages & ordering | `01-complete-reference.sh` §2, `/orders/keyed` |
| Consumer groups & offsets | `01-complete-reference.sh` §4 |
| Offset reset & replay | `01-complete-reference.sh` §4 |
| Broker failure & leader election | `01-complete-reference.sh` §5 |
| ISR (In-Sync Replicas) | `docker-compose.yml` `MIN_INSYNC_REPLICAS=2` |
| Manual acknowledgment | `KafkaConsumerService.java` `consumeOrder()` |
| Retry + DLQ | `KafkaConsumerService.java` `@RetryableTopic`, `/dlq` |
| Batch consumption | `KafkaConsumerService.java` `consumeNotificationsBatch()` |
| Log compaction | `01-complete-reference.sh` §7, `/profiles` |
| Tombstone (GDPR delete) | `03-transactions-and-tombstones.sh` §4, `DELETE /profiles/{userId}` |
| Idempotent producer | `application.properties` `enable.idempotence=true` |
| Transactional producer | `KafkaProducerConfig.java`, `/orders/transactional` |
| Atomic multi-topic write | `KafkaProducerService.java` `sendTransactional()` |
| Exactly-once semantics | `03-transactions-and-tombstones.sh` §1-3 |
| Zombie fencing | `03-transactions-and-tombstones.sh` §6 |
| read_committed isolation | `03-transactions-and-tombstones.sh` §3 |
| Message headers | `KafkaProducerService.java` `sendWithHeaders()` |
| Producer interceptor | `KafkaProducerConfig.ProducerAuditInterceptor` |
| Synchronous send | `KafkaProducerService.java` `sendSync()` |
| Consumer pause / resume | `KafkaConsumerService.java`, `/consumers/{id}/pause` |
| Compression (snappy/lz4/zstd) | `application.properties`, `04-advanced-producer-consumer.sh` §2 |
| Producer & consumer quotas | `04-advanced-producer-consumer.sh` §5 |
| Consumer lag alerting | `04-advanced-producer-consumer.sh` §4 |
| Multi-group fan-out | `04-advanced-producer-consumer.sh` §6 |
| Header inspection | `04-advanced-producer-consumer.sh` §7 |
| Partition reassignment | `04-advanced-producer-consumer.sh` §8 |
| Performance testing | `01-complete-reference.sh` §8 |
| Schema evolution | `02-system-design-scenarios.sh` Scenario 8 |
| Event-driven microservices | `02-system-design-scenarios.sh` Scenario 1 |
| Consumer group rebalancing | `02-system-design-scenarios.sh` Scenario 4 |
| Incident recovery | `02-system-design-scenarios.sh` Scenario 10 |
| Audit events (long retention) | `KafkaTopicConfig.java`, `/audit` |

---

## API Reference

| Method | Endpoint | Feature |
|---|---|---|
| POST | `/api/kafka/orders` | Basic produce (round-robin) |
| POST | `/api/kafka/orders/keyed` | Keyed produce (ordering) |
| POST | `/api/kafka/orders/partition/{id}` | Produce to specific partition |
| POST | `/api/kafka/orders/headers` | Produce with custom headers |
| POST | `/api/kafka/orders/sync` | Synchronous produce |
| POST | `/api/kafka/orders/batch?count=N` | Batch produce |
| POST | `/api/kafka/orders/transactional` | Atomic multi-topic transaction |
| POST | `/api/kafka/payments` | Payment events + retry/DLQ |
| POST | `/api/kafka/notifications` | Batch consumer demo |
| POST | `/api/kafka/dlq` | Manual DLQ routing |
| POST | `/api/kafka/profiles` | Compacted topic (upsert) |
| DELETE | `/api/kafka/profiles/{userId}` | Tombstone (GDPR delete) |
| POST | `/api/kafka/stocks` | Ordering per symbol |
| POST | `/api/kafka/audit` | Audit events (90-day retention) |
| POST | `/api/kafka/consumers/{id}/pause` | Pause a consumer |
| POST | `/api/kafka/consumers/{id}/resume` | Resume a consumer |
| GET | `/api/kafka/consumers/{id}/status` | Consumer paused status |
| GET | `/api/kafka/messages` | View consumed messages |
| DELETE | `/api/kafka/messages` | Clear consumed messages |

### Consumer Listener IDs (for pause/resume)
| ID | Topic | Group |
|---|---|---|
| `orders-listener` | orders | order-processor |
| `payments-listener` | payments | payment-processor |
| `notifications-listener` | notifications | notification-processor |
| `dlq-listener` | orders-dlq | dlq-monitor |
| `stocks-listener` | stock-prices | stock-processor |
| `audit-listener` | audit-events | audit-processor |
| `inventory-listener` | inventory-updates | inventory-processor |
