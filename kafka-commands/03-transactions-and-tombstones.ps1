# =============================================================================
# KAFKA TRANSACTIONS, EXACTLY-ONCE & TOMBSTONES (Windows PowerShell Version)
# Covers: transactional producers, atomic multi-topic writes, tombstones,
#         idempotent producers, read_committed isolation
# =============================================================================

$BROKERS = "localhost:9092,localhost:9093,localhost:9094"

# =============================================================================
# SECTION 1 — IDEMPOTENT PRODUCER
#
# Problem: Producer retries on network timeout → broker may write duplicate
# Solution: enable.idempotence=true assigns each producer a PID + sequence number
#           Broker deduplicates based on (PID, partition, sequence)
#
# Use case: Payment processing — never charge a customer twice
# =============================================================================

docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --create --topic payments --partitions 6 --replication-factor 3

# Idempotent producer — safe to retry, broker deduplicates automatically
docker exec -it kafka-1 kafka-console-producer `
  --bootstrap-server $BROKERS --topic payments `
  --producer-property enable.idempotence=true `
  --producer-property acks=all `
  --producer-property retries=2147483647 `
  --producer-property max.in.flight.requests.per.connection=5
# > {"txnId":"txn-001","from":"acc-A","to":"acc-B","amount":500.00}
# Even if this message is retried 10 times, broker stores it exactly once

# Verify offset count — should be exactly 1 even after retries
docker exec -it kafka-1 kafka-run-class kafka.tools.GetOffsetShell `
  --bootstrap-server $BROKERS --topic payments --time -1

# =============================================================================
# SECTION 2 — TRANSACTIONAL PRODUCER (Atomic Multi-Topic Write)
#
# Problem: You need to write to orders AND inventory atomically
#          If inventory write fails, the order write must also be rolled back
# Solution: Kafka transactions — all writes in a transaction are atomic
#
# Use case: E-commerce checkout — debit inventory AND publish order event
#           Both succeed or neither is visible to consumers
# =============================================================================

docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --create --topic orders --partitions 3 --replication-factor 3

docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --create --topic inventory-updates --partitions 3 --replication-factor 3

# Transactional producer requires:
#   transactional.id — unique per producer instance (survives restarts)
#   acks=all — required for transactions
#   enable.idempotence=true — required for transactions
docker exec -it kafka-1 kafka-console-producer `
  --bootstrap-server $BROKERS --topic orders `
  --producer-property transactional.id=checkout-producer-1 `
  --producer-property acks=all `
  --producer-property enable.idempotence=true
# > {"orderId":"o1","userId":"u1","item":"MacBook","total":2499.99}
# Note: console producer doesn't support multi-topic transactions natively
# Use the Spring Boot /api/kafka/orders/transactional endpoint for full demo

# =============================================================================
# SECTION 3 — READ_COMMITTED ISOLATION (Consumer Side)
#
# Problem: Consumer reads messages from an in-progress transaction (dirty read)
# Solution: isolation.level=read_committed — only see committed transactions
#
# Default is read_uncommitted — consumers see all messages including aborted ones
# =============================================================================

# Consumer with read_committed — only sees fully committed transactions
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders `
  --group txn-safe-consumer `
  --consumer-property isolation.level=read_committed `
  --from-beginning

# Consumer with read_uncommitted (default) — sees everything including aborted
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders `
  --group txn-unsafe-consumer `
  --consumer-property isolation.level=read_uncommitted `
  --from-beginning

# =============================================================================
# SECTION 4 — TOMBSTONES (Log Compaction Deletion)
#
# Problem: User deletes account — GDPR requires removing their data
# Solution: Send a tombstone (null value) for the user's key
#           During compaction, Kafka deletes all records for that key
#
# Only works on compacted topics (cleanup.policy=compact)
# =============================================================================

docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --create --topic user-profiles `
  --partitions 3 --replication-factor 3 `
  --config cleanup.policy=compact `
  --config min.cleanable.dirty.ratio=0.01 `
  --config segment.ms=100 `
  --config delete.retention.ms=100

# Write user profile
docker exec -it kafka-1 kafka-console-producer `
  --bootstrap-server $BROKERS --topic user-profiles `
  --property parse.key=true --property key.separator=:
# > user-1:{"name":"Alice","email":"alice@example.com","tier":"gold"}
# > user-2:{"name":"Bob","email":"bob@example.com","tier":"silver"}

# Send tombstone for user-2 (GDPR deletion)
# Empty value after the colon = null value = tombstone
docker exec -it kafka-1 bash -c `
  "echo 'user-2:' | kafka-console-producer --bootstrap-server $BROKERS --topic user-profiles --property parse.key=true --property key.separator=:"

# After compaction: user-1's latest record survives, user-2 is deleted
# Verify by consuming — user-2 should not appear after compaction runs
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic user-profiles `
  --from-beginning --property print.key=true

# =============================================================================
# SECTION 5 — TRANSACTION COORDINATOR & __transaction_state TOPIC
#
# Kafka uses an internal topic to track transaction state
# Each transactional.id maps to a transaction coordinator broker
# =============================================================================

# List internal topics (__ prefix = Kafka internal)
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --list | findstr /R "^__"
# __consumer_offsets — stores committed consumer offsets
# __transaction_state — stores transaction state (begin, commit, abort)

# Describe transaction state topic
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --describe --topic __transaction_state

# =============================================================================
# SECTION 6 — ZOMBIE FENCING
#
# Problem: Old producer instance comes back after a restart and writes stale data
# Solution: transactional.id + epoch — new producer instance fences out old one
#
# When a new producer initializes with the same transactional.id:
#   - Kafka increments the epoch for that transactional.id
#   - Any write from the old producer (lower epoch) is rejected with ProducerFencedException
# =============================================================================

# Producer 1 starts with transactional.id=checkout-1
docker exec -it kafka-1 kafka-console-producer `
  --bootstrap-server $BROKERS --topic orders `
  --producer-property transactional.id=checkout-1 `
  --producer-property acks=all

# Producer 2 starts with SAME transactional.id=checkout-1 (simulates restart)
# This fences out Producer 1 — any further writes from P1 will fail
docker exec -it kafka-1 kafka-console-producer `
  --bootstrap-server $BROKERS --topic orders `
  --producer-property transactional.id=checkout-1 `
  --producer-property acks=all
