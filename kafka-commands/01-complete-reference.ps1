# =============================================================================
# KAFKA COMPLETE COMMAND REFERENCE — A to Z (Windows PowerShell Version)
# Cluster: 3 brokers on localhost:9092, 9093, 9094
# Start cluster first: docker-compose up -d
# Open Kafka UI at: http://localhost:8090
# =============================================================================

$BROKERS = "localhost:9092,localhost:9093,localhost:9094"

# =============================================================================
# SECTION 1 — TOPIC MANAGEMENT
# Scenario: E-commerce platform with orders, payments, notifications
# =============================================================================

# Create topic: orders — 3 partitions, 3 replicas (production-grade HA)
# 3 replicas = data survives 2 broker failures
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --create --topic orders --partitions 3 --replication-factor 3

# Create topic: payments — 6 partitions for higher throughput
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --create --topic payments --partitions 6 --replication-factor 3

# Create topic: notifications — lower volume, 2 partitions
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --create --topic notifications --partitions 2 --replication-factor 3

# Create Dead Letter Queue (DLQ) — receives messages that failed processing
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --create --topic orders-dlq --partitions 3 --replication-factor 3

# Create topic with short retention (1 hour) — good for ephemeral events like click-streams
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --create --topic clickstream `
  --partitions 3 --replication-factor 3 `
  --config retention.ms=3600000

# Create compacted topic — only latest value per key is kept
# Use case: user-profiles, product catalog (acts like a distributed KV store)
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --create --topic user-profiles `
  --partitions 3 --replication-factor 3 `
  --config cleanup.policy=compact `
  --config min.cleanable.dirty.ratio=0.1 `
  --config segment.ms=100

# List all topics
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS --list

# Describe a topic — shows partition leaders, replicas, ISR (In-Sync Replicas)
# ISR < replicas means a broker is lagging or down — alert on this!
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --describe --topic orders

# Increase partitions (ONLY increase is allowed, never decrease)
# Do this when throughput grows and consumers can't keep up
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --alter --topic payments --partitions 9

# Delete a topic
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --delete --topic clickstream

# =============================================================================
# SECTION 2 — PRODUCING MESSAGES
# Scenario: Order service publishing events
# =============================================================================

# Produce a simple message (interactive — type message then Enter)
docker exec -it kafka-1 kafka-console-producer `
  --bootstrap-server $BROKERS --topic orders
# > {"orderId":"1","status":"PLACED","amount":250.00}

# Produce with a KEY — critical for ordering guarantees
# Same key always routes to same partition = ordered processing per user
docker exec -it kafka-1 kafka-console-producer `
  --bootstrap-server $BROKERS --topic orders `
  --property "parse.key=true" --property "key.separator=:"
# > user-123:{"orderId":"1","userId":"user-123","status":"PLACED"}
# > user-123:{"orderId":"2","userId":"user-123","status":"SHIPPED"}
# Both messages for user-123 land in the same partition — order preserved

# Produce with acks=all — strongest durability guarantee
# Message is only acknowledged after ALL in-sync replicas write it
docker exec -it kafka-1 kafka-console-producer `
  --bootstrap-server $BROKERS --topic payments `
  --producer-property acks=all `
  --producer-property retries=5 `
  --producer-property enable.idempotence=true

# Produce from a file (batch ingestion)
docker exec -it kafka-1 bash -c `
  "echo '{`"orderId`":`"100`",`"status`":`"PLACED`"}' | kafka-console-producer --bootstrap-server $BROKERS --topic orders"

# =============================================================================
# SECTION 3 — CONSUMING MESSAGES
# Scenario: Notification service consuming order events
# =============================================================================

# Consume from the beginning — replay all historical messages
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders --from-beginning

# Consume only new messages (default — starts from latest offset)
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders

# Consume with key and timestamp printed
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders --from-beginning `
  --property print.key=true `
  --property print.timestamp=true `
  --property key.separator=" | "

# Consume as part of a consumer group
# Multiple consumers in same group share partitions (load balancing)
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders `
  --group notification-service --from-beginning

# Consume from a specific partition only
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders `
  --partition 0 --offset earliest

# Consume exactly N messages then exit
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders `
  --from-beginning --max-messages 5

# =============================================================================
# SECTION 4 — CONSUMER GROUPS & OFFSETS
# Scenario: Multiple microservices consuming orders independently
# Each group maintains its own offset — they don't interfere with each other
# =============================================================================

# List all consumer groups
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS --list

# Describe a group — shows LAG per partition
# LAG = (log-end-offset) - (current-offset) = unprocessed messages
# High lag = consumer is falling behind — scale up consumers!
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS --describe --group notification-service

# Describe all groups at once
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS --describe --all-groups

# Reset offset to EARLIEST — replay ALL messages
# Use case: deployed a bug fix, need to reprocess all historical orders
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS `
  --group notification-service --topic orders `
  --reset-offsets --to-earliest --execute

# Reset offset to LATEST — skip all existing, start fresh
# Use case: consumer was down for days, old messages are irrelevant
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS `
  --group notification-service --topic orders `
  --reset-offsets --to-latest --execute

# Reset to specific offset number
# Use case: you know exactly where the good data starts
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS `
  --group notification-service --topic orders `
  --reset-offsets --to-offset 42 --execute

# Reset by datetime — replay from a specific point in time
# Use case: incident at 2pm, replay everything after 1pm to recover
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS `
  --group notification-service --topic orders `
  --reset-offsets --to-datetime 2024-01-15T13:00:00.000 --execute

# Shift offset backwards by N — reprocess last 100 messages
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS `
  --group notification-service --topic orders `
  --reset-offsets --shift-by -100 --execute

# Delete a stale consumer group
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS --delete --group notification-service

# =============================================================================
# SECTION 5 — REPLICATION & FAULT TOLERANCE
# Scenario: Broker failure during peak traffic
# =============================================================================

# Check partition leaders and ISR before failure
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --describe --topic orders
# Output: Leader: 1  Replicas: 1,2,3  Isr: 1,2,3  <- healthy

# SIMULATE BROKER FAILURE: stop kafka-2
docker stop kafka-2
# Kafka detects failure via Zookeeper heartbeat timeout (~10s)
# Leader election happens automatically for partitions led by broker-2

# Check topic after failure — new leaders elected, ISR shrinks
docker exec -it kafka-1 kafka-topics --bootstrap-server localhost:9092,localhost:9094 `
  --describe --topic orders
# Output: Leader: 1 or 3  Isr: 1,3  <- broker-2 removed from ISR

# Producers/consumers continue working because min.insync.replicas=2
# and we still have 2 brokers (1 and 3) in ISR

# Restart kafka-2 — it catches up and rejoins ISR
docker start kafka-2

# After recovery, trigger preferred leader election to rebalance
# Without this, all partitions stay on brokers 1 and 3 (uneven load)
docker exec -it kafka-1 kafka-leader-election `
  --bootstrap-server $BROKERS `
  --election-type PREFERRED --all-topic-partitions

# =============================================================================
# SECTION 6 — DYNAMIC TOPIC CONFIGURATION
# Scenario: Tuning topics in production without downtime
# =============================================================================

# Extend retention to 30 days for compliance (financial records)
docker exec -it kafka-1 kafka-configs `
  --bootstrap-server $BROKERS `
  --entity-type topics --entity-name orders `
  --alter --add-config retention.ms=2592000000

# Increase max message size to 5MB (for large payloads like images metadata)
docker exec -it kafka-1 kafka-configs `
  --bootstrap-server $BROKERS `
  --entity-type topics --entity-name orders `
  --alter --add-config max.message.bytes=5242880

# Enable snappy compression (reduces storage and network by ~50%)
docker exec -it kafka-1 kafka-configs `
  --bootstrap-server $BROKERS `
  --entity-type topics --entity-name orders `
  --alter --add-config compression.type=snappy

# View all config overrides for a topic
docker exec -it kafka-1 kafka-configs `
  --bootstrap-server $BROKERS `
  --entity-type topics --entity-name orders --describe

# Remove a config override (revert to broker default)
docker exec -it kafka-1 kafka-configs `
  --bootstrap-server $BROKERS `
  --entity-type topics --entity-name orders `
  --alter --delete-config retention.ms

# =============================================================================
# SECTION 7 — LOG COMPACTION
# Scenario: User profile service — only latest state per user matters
# =============================================================================

# Produce multiple updates for same key
docker exec -it kafka-1 kafka-console-producer `
  --bootstrap-server $BROKERS --topic user-profiles `
  --property "parse.key=true" --property "key.separator=:"
# > user-1:{"name":"Alice","email":"alice@old.com","tier":"silver"}
# > user-1:{"name":"Alice","email":"alice@new.com","tier":"gold"}   <- survives
# > user-2:{"name":"Bob","email":"bob@example.com","tier":"bronze"}

# Produce a tombstone (null value = delete this key during compaction)
# Use case: user deleted their account — GDPR compliance
docker exec -it kafka-1 bash -c `
  "echo 'user-2:' | kafka-console-producer --bootstrap-server $BROKERS --topic user-profiles --property parse.key=true --property key.separator=:"

# After compaction runs, only user-1's latest record remains

# =============================================================================
# SECTION 8 — PERFORMANCE TESTING
# Scenario: Load testing before Black Friday sale
# =============================================================================

# Producer throughput test — 1 million 1KB messages, unlimited throughput
docker exec -it kafka-1 kafka-producer-perf-test `
  --topic orders --num-records 1000000 --record-size 1024 `
  --throughput -1 `
  --producer-props bootstrap.servers=$BROKERS acks=all

# Producer test with throttle — simulate 10k msg/sec sustained load
docker exec -it kafka-1 kafka-producer-perf-test `
  --topic orders --num-records 100000 --record-size 1024 `
  --throughput 10000 `
  --producer-props bootstrap.servers=$BROKERS acks=1

# Consumer throughput test
docker exec -it kafka-1 kafka-consumer-perf-test `
  --bootstrap-server $BROKERS `
  --topic orders --messages 1000000 --group perf-test-group

# =============================================================================
# SECTION 9 — MONITORING & OBSERVABILITY
# =============================================================================

# Get log-end offsets (total messages written per partition)
docker exec -it kafka-1 kafka-run-class kafka.tools.GetOffsetShell `
  --bootstrap-server $BROKERS --topic orders --time -1

# Get earliest available offsets (how much data is retained)
docker exec -it kafka-1 kafka-run-class kafka.tools.GetOffsetShell `
  --bootstrap-server $BROKERS --topic orders --time -2

# Dump raw log segment (inspect messages on disk — debugging)
docker exec -it kafka-1 kafka-dump-log `
  --files /var/lib/kafka/data/orders-0/00000000000000000000.log `
  --print-data-log

# Check broker API versions (verify broker capabilities)
docker exec -it kafka-1 kafka-broker-api-versions `
  --bootstrap-server $BROKERS

# =============================================================================
# SECTION 10 — CLEANUP
# =============================================================================

# Stop cluster (keeps data volumes)
docker-compose down

# Stop cluster and wipe ALL data (fresh start)
docker-compose down -v
