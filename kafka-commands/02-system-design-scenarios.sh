#!/bin/bash
# =============================================================================
# KAFKA SYSTEM DESIGN SCENARIOS
# Real-world architectures and how Kafka solves them
# =============================================================================

BROKERS="localhost:9092,localhost:9093,localhost:9094"

# =============================================================================
# SCENARIO 1: EVENT-DRIVEN MICROSERVICES (E-Commerce Order Flow)
#
# Architecture:
#   OrderService --> [orders topic] --> PaymentService
#                                   --> InventoryService
#                                   --> NotificationService
#
# Each service has its own consumer group = independent processing
# One service being slow/down does NOT affect others
# =============================================================================

docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS \
  --create --topic orders --partitions 3 --replication-factor 3

# Simulate OrderService publishing an order
docker exec -it kafka-1 kafka-console-producer \
  --bootstrap-server $BROKERS --topic orders \
  --property parse.key=true --property key.separator=:
# > order-1:{"orderId":"1","userId":"u1","items":["item-A"],"total":99.99}

# PaymentService consumes (group: payment-svc)
# Open terminal 1:
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server $BROKERS --topic orders \
  --group payment-svc --from-beginning

# InventoryService consumes independently (group: inventory-svc)
# Open terminal 2:
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server $BROKERS --topic orders \
  --group inventory-svc --from-beginning

# Both groups receive the SAME message — fan-out pattern
# Check each group's offset independently:
docker exec -it kafka-1 kafka-consumer-groups \
  --bootstrap-server $BROKERS --describe --group payment-svc
docker exec -it kafka-1 kafka-consumer-groups \
  --bootstrap-server $BROKERS --describe --group inventory-svc

# =============================================================================
# SCENARIO 2: RETRY + DEAD LETTER QUEUE (DLQ)
#
# Architecture:
#   [orders] --> ConsumerService
#                  |-- success --> commit offset
#                  |-- failure (retry 1,2,3) --> [orders-retry]
#                  |-- exhausted retries --> [orders-dlq]
#
# Use case: Payment gateway is down, retry with backoff, eventually DLQ
# =============================================================================

docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS \
  --create --topic orders-retry --partitions 3 --replication-factor 3

docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS \
  --create --topic orders-dlq --partitions 3 --replication-factor 3

# Produce a "bad" order that will fail processing
docker exec -it kafka-1 kafka-console-producer \
  --bootstrap-server $BROKERS --topic orders
# > {"orderId":"FAIL-1","userId":"u1","total":-1}   <- invalid, will fail

# Simulate moving failed message to DLQ after retries exhausted
docker exec -it kafka-1 kafka-console-producer \
  --bootstrap-server $BROKERS --topic orders-dlq
# > {"orderId":"FAIL-1","error":"Invalid total","retryCount":3,"originalTopic":"orders"}

# Monitor DLQ for failed messages (ops team reviews these)
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server $BROKERS --topic orders-dlq --from-beginning

# =============================================================================
# SCENARIO 3: EXACTLY-ONCE SEMANTICS (Financial Transactions)
#
# Problem: Network retry causes duplicate payment processing
# Solution: Idempotent producer + transactional consumer
#
# Producer config for exactly-once:
#   enable.idempotence=true
#   acks=all
#   transactional.id=payment-producer-1
# =============================================================================

docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS \
  --create --topic payments --partitions 6 --replication-factor 3

# Produce with idempotence enabled (no duplicates even on retry)
docker exec -it kafka-1 kafka-console-producer \
  --bootstrap-server $BROKERS --topic payments \
  --producer-property enable.idempotence=true \
  --producer-property acks=all \
  --producer-property retries=2147483647 \
  --producer-property max.in.flight.requests.per.connection=5
# > {"txnId":"txn-001","from":"acc-A","to":"acc-B","amount":500.00}

# Verify no duplicates by checking offsets
docker exec -it kafka-1 kafka-run-class kafka.tools.GetOffsetShell \
  --bootstrap-server $BROKERS --topic payments --time -1

# =============================================================================
# SCENARIO 4: CONSUMER GROUP REBALANCING
#
# Problem: Adding/removing consumers triggers rebalance — brief pause
# Solution: Static membership to reduce rebalance frequency
#
# Use case: Kubernetes pod rolling update — don't rebalance on every restart
# =============================================================================

docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS \
  --create --topic inventory-updates --partitions 6 --replication-factor 3

# Consumer with static membership (group.instance.id = stable pod identity)
# Rebalance only happens after session.timeout.ms (not on every restart)
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server $BROKERS --topic inventory-updates \
  --group inventory-svc \
  --consumer-property group.instance.id=pod-0 \
  --consumer-property session.timeout.ms=60000

# Watch rebalance events by describing the group while adding consumers
docker exec -it kafka-1 kafka-consumer-groups \
  --bootstrap-server $BROKERS --describe --group inventory-svc

# =============================================================================
# SCENARIO 5: MULTI-REGION / DISASTER RECOVERY
#
# Architecture:
#   Region A: kafka-1, kafka-2, kafka-3 (primary)
#   Region B: kafka-4, kafka-5, kafka-6 (DR — MirrorMaker replicates)
#
# Simulate: Primary region goes down, failover to DR
# =============================================================================

# Check current state of primary cluster
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS \
  --describe --topic orders

# Simulate primary failure
docker stop kafka-1 kafka-2 kafka-3

# In real DR: update consumer bootstrap.servers to Region B brokers
# Consumers resume from last committed offset (no data loss)

# Restart primary
docker start kafka-1 kafka-2 kafka-3

# =============================================================================
# SCENARIO 6: BACKPRESSURE & LAG MANAGEMENT
#
# Problem: Consumer is slow, lag keeps growing (e.g., DB writes are slow)
# Solutions:
#   1. Add more consumers (up to partition count)
#   2. Increase consumer fetch size
#   3. Process in batches
# =============================================================================

# Check current lag
docker exec -it kafka-1 kafka-consumer-groups \
  --bootstrap-server $BROKERS --describe --group payment-svc
# CONSUMER-ID  HOST  CLIENT-ID  #PARTITIONS  LOG-END-OFFSET  LAG
# If LAG is growing, you need to scale consumers

# Add more consumers to the group (open in new terminals)
# Each consumer gets assigned partitions automatically
# Max useful consumers = number of partitions (3 for orders topic)
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server $BROKERS --topic orders --group payment-svc
# Open 2 more terminals with same command — 3 consumers, 3 partitions, 0 lag

# =============================================================================
# SCENARIO 7: MESSAGE ORDERING GUARANTEE
#
# Kafka guarantees order WITHIN a partition, not across partitions
# Solution: Use a consistent key so related messages go to same partition
#
# Use case: Stock price updates — must process in order per stock symbol
# =============================================================================

docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS \
  --create --topic stock-prices --partitions 6 --replication-factor 3

# Key = stock symbol → all AAPL updates go to same partition, in order
docker exec -it kafka-1 kafka-console-producer \
  --bootstrap-server $BROKERS --topic stock-prices \
  --property parse.key=true --property key.separator=:
# > AAPL:{"symbol":"AAPL","price":182.50,"ts":1700000001}
# > AAPL:{"symbol":"AAPL","price":183.10,"ts":1700000002}
# > AAPL:{"symbol":"AAPL","price":181.90,"ts":1700000003}
# > GOOG:{"symbol":"GOOG","price":140.20,"ts":1700000001}
# AAPL messages are always in order; GOOG is independent

# Verify by consuming from specific partition
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server $BROKERS --topic stock-prices \
  --partition 0 --offset earliest \
  --property print.key=true

# =============================================================================
# SCENARIO 8: SCHEMA EVOLUTION (Backward/Forward Compatibility)
#
# Problem: Producer adds a new field — old consumers must still work
# Solution: Use Schema Registry (running on port 8081)
# =============================================================================

# Register a schema (v1)
curl -X POST http://localhost:8081/subjects/orders-value/versions \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d '{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"fields\":[{\"name\":\"orderId\",\"type\":\"string\"},{\"name\":\"total\",\"type\":\"double\"}]}"}'

# Register schema v2 (added optional field — backward compatible)
curl -X POST http://localhost:8081/subjects/orders-value/versions \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d '{"schema":"{\"type\":\"record\",\"name\":\"Order\",\"fields\":[{\"name\":\"orderId\",\"type\":\"string\"},{\"name\":\"total\",\"type\":\"double\"},{\"name\":\"currency\",\"type\":[\"null\",\"string\"],\"default\":null}]}"}'

# List all schema versions
curl http://localhost:8081/subjects/orders-value/versions

# Get a specific version
curl http://localhost:8081/subjects/orders-value/versions/1

# =============================================================================
# SCENARIO 9: TOPIC PARTITIONING STRATEGY
#
# How to choose partition count:
#   - Target throughput / single-partition throughput
#   - Example: need 300 MB/s, single partition does 30 MB/s → 10 partitions
#   - Rule of thumb: 2-3x the number of consumers you expect
# =============================================================================

# Create topic sized for expected load
# 100k orders/sec, each consumer handles 10k/sec → 10 partitions
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS \
  --create --topic high-volume-orders \
  --partitions 12 --replication-factor 3

# Verify partition distribution across brokers
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS \
  --describe --topic high-volume-orders
# Each broker should lead ~4 partitions (12/3 brokers = balanced)

# =============================================================================
# SCENARIO 10: INCIDENT RECOVERY — REPLAY FROM TIMESTAMP
#
# Problem: Bug deployed at 14:00, fixed at 15:00
# All messages from 13:45 to 15:00 were processed incorrectly
# Solution: Reset offset to 13:45 and replay
# =============================================================================

# Step 1: Stop the buggy consumer (deploy fix first!)
# Step 2: Reset offset to before the incident
docker exec -it kafka-1 kafka-consumer-groups \
  --bootstrap-server $BROKERS \
  --group payment-svc --topic orders \
  --reset-offsets --to-datetime 2024-01-15T13:45:00.000 --execute

# Step 3: Start fixed consumer — it replays from 13:45
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server $BROKERS --topic orders \
  --group payment-svc

# Step 4: Monitor lag to confirm catch-up
docker exec -it kafka-1 kafka-consumer-groups \
  --bootstrap-server $BROKERS --describe --group payment-svc
