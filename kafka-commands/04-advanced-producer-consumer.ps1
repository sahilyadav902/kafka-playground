# =============================================================================
# KAFKA ADVANCED PRODUCER & CONSUMER FEATURES (Windows PowerShell Version)
# Covers: interceptors, compression, pause/resume, quotas, consumer lag
#         alerting, header inspection, multi-consumer group fan-out
# =============================================================================

$BROKERS = "localhost:9092,localhost:9093,localhost:9094"

# =============================================================================
# SECTION 1 — PRODUCER INTERCEPTORS
#
# Interceptors run before every send (onSend) and after every ack (onAcknowledgement)
# Use cases:
#   - Inject trace-id / correlation-id headers automatically
#   - Emit metrics (messages sent, bytes sent, error rate)
#   - Audit logging without changing application code
#
# In Spring Boot: configured via ProducerConfig.INTERCEPTOR_CLASSES_CONFIG
# See: KafkaProducerConfig.ProducerAuditInterceptor
# =============================================================================

# Verify interceptor-injected header is present on consumed messages
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders `
  --from-beginning `
  --property print.headers=true `
  --property print.key=true
# Look for "produced-at" header in output — injected by ProducerAuditInterceptor

# =============================================================================
# SECTION 2 — COMPRESSION
#
# Kafka supports: none, gzip, snappy, lz4, zstd
# Compression happens at the producer, decompression at the consumer
# Broker stores compressed batches as-is (no re-compression)
#
# Comparison:
#   gzip  — best compression ratio, highest CPU
#   snappy — good balance (Google's algorithm), low CPU
#   lz4   — fastest compression/decompression, moderate ratio
#   zstd  — best ratio + good speed (Kafka 2.1+), recommended for new deployments
# =============================================================================

# Create topic and produce with different compression types to compare
docker exec -it kafka-1 kafka-topics --bootstrap-server $BROKERS `
  --create --topic compression-test --partitions 3 --replication-factor 3

# Produce with snappy compression
docker exec -it kafka-1 kafka-producer-perf-test `
  --topic compression-test --num-records 100000 --record-size 1024 `
  --throughput -1 `
  --producer-props bootstrap.servers=$BROKERS compression.type=snappy

# Produce with lz4 compression
docker exec -it kafka-1 kafka-producer-perf-test `
  --topic compression-test --num-records 100000 --record-size 1024 `
  --throughput -1 `
  --producer-props bootstrap.servers=$BROKERS compression.type=lz4

# Produce with zstd compression (best for new deployments)
docker exec -it kafka-1 kafka-producer-perf-test `
  --topic compression-test --num-records 100000 --record-size 1024 `
  --throughput -1 `
  --producer-props bootstrap.servers=$BROKERS compression.type=zstd

# Check actual disk usage after compression
docker exec -it kafka-1 du -sh /var/lib/kafka/data/compression-test-*

# =============================================================================
# SECTION 3 — CONSUMER PAUSE / RESUME
#
# Use case: Downstream database is overloaded
#   1. Pause consumer — Kafka retains messages, no data loss
#   2. Wait for DB to recover
#   3. Resume consumer — picks up exactly where it left off
#
# Via Spring Boot API:
#   POST /api/kafka/consumers/orders-listener/pause
#   POST /api/kafka/consumers/orders-listener/resume
#   GET  /api/kafka/consumers/orders-listener/status
#
# Available listener IDs (defined in @KafkaListener id= attribute):
#   orders-listener, payments-listener, notifications-listener,
#   dlq-listener, stocks-listener, audit-listener, inventory-listener
# =============================================================================

# Pause the orders consumer via API
curl.exe -X POST http://localhost:8081/api/kafka/consumers/orders-listener/pause

# Check status — should show paused: true
curl.exe http://localhost:8081/api/kafka/consumers/orders-listener/status

# Produce messages while consumer is paused — they accumulate in Kafka
docker exec -it kafka-1 kafka-console-producer `
  --bootstrap-server $BROKERS --topic orders
# > {"orderId":"queued-1","userId":"u1","status":"PLACED"}
# > {"orderId":"queued-2","userId":"u2","status":"PLACED"}

# Check lag growing while paused
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS --describe --group order-processor

# Resume — consumer catches up immediately
curl.exe -X POST http://localhost:8081/api/kafka/consumers/orders-listener/resume

# Verify lag drops back to 0
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS --describe --group order-processor

# =============================================================================
# SECTION 4 — CONSUMER LAG ALERTING
#
# Consumer lag = log-end-offset - current-offset
# High lag = consumer is falling behind = potential data loss risk
#
# Alert thresholds (example):
#   WARNING: lag > 1000 messages
#   CRITICAL: lag > 10000 messages
#
# In production: use Kafka Exporter + Prometheus + Grafana
# =============================================================================

# PowerShell function to check lag and alert if above threshold
function Check-ConsumerLag {
    param(
        [string]$Group,
        [string]$Topic,
        [int]$Threshold = 1000
    )

    $output = docker exec kafka-1 kafka-consumer-groups `
        --bootstrap-server $BROKERS `
        --describe --group $Group 2>$null

    $lag = $output | Where-Object { $_ -match $Topic } | `
        ForEach-Object { $_ -split '\s+' } | `
        ForEach-Object { if ($_ -match '^\d+$') { [int]$_ } } | `
        Select-Object -Last 1

    Write-Host "Group: $Group | Topic: $Topic | Total Lag: $lag"
    if ($lag -gt $Threshold) {
        Write-Host "ALERT: Lag $lag exceeds threshold $Threshold — scale up consumers!"
    }
}

Check-ConsumerLag -Group "order-processor" -Topic "orders" -Threshold 1000
Check-ConsumerLag -Group "payment-processor" -Topic "payments" -Threshold 500

# =============================================================================
# SECTION 5 — PRODUCER & CONSUMER QUOTAS
#
# Quotas prevent a single client from overwhelming the cluster
# Types:
#   producer_byte_rate — max bytes/sec a producer can send
#   consumer_byte_rate — max bytes/sec a consumer can fetch
#   request_percentage — max % of broker request handler threads a client can use
# =============================================================================

# Set producer quota: limit order-service to 10 MB/s
docker exec -it kafka-1 kafka-configs `
  --bootstrap-server $BROKERS `
  --entity-type clients --entity-name order-service `
  --alter --add-config producer_byte_rate=10485760

# Set consumer quota: limit analytics-service to 5 MB/s
docker exec -it kafka-1 kafka-configs `
  --bootstrap-server $BROKERS `
  --entity-type clients --entity-name analytics-service `
  --alter --add-config consumer_byte_rate=5242880

# Set user-level quota (applies to all clients for that user)
docker exec -it kafka-1 kafka-configs `
  --bootstrap-server $BROKERS `
  --entity-type users --entity-name alice `
  --alter --add-config producer_byte_rate=20971520,consumer_byte_rate=20971520

# View quotas
docker exec -it kafka-1 kafka-configs `
  --bootstrap-server $BROKERS `
  --entity-type clients --entity-name order-service --describe

# Remove quota
docker exec -it kafka-1 kafka-configs `
  --bootstrap-server $BROKERS `
  --entity-type clients --entity-name order-service `
  --alter --delete-config producer_byte_rate

# =============================================================================
# SECTION 6 — MULTI-CONSUMER GROUP FAN-OUT
#
# Same topic consumed by multiple independent groups simultaneously
# Each group gets ALL messages — no sharing, no interference
#
# Use case: orders topic consumed by:
#   payment-svc    — charges the customer
#   inventory-svc  — reserves stock
#   analytics-svc  — updates dashboards
#   audit-svc      — writes to audit log
# =============================================================================

# Start 4 independent consumer groups on the same topic
# Open 4 terminals:

# Terminal 1 — payment service
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders `
  --group payment-svc --from-beginning

# Terminal 2 — inventory service
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders `
  --group inventory-svc --from-beginning

# Terminal 3 — analytics service
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders `
  --group analytics-svc --from-beginning

# Terminal 4 — audit service
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders `
  --group audit-svc --from-beginning

# Produce one message — all 4 groups receive it independently
docker exec -it kafka-1 kafka-console-producer `
  --bootstrap-server $BROKERS --topic orders
# > {"orderId":"fan-out-1","userId":"u1","total":99.99}

# Verify all 4 groups have offset 1
docker exec -it kafka-1 kafka-consumer-groups `
  --bootstrap-server $BROKERS --describe --all-groups | findstr "orders"

# =============================================================================
# SECTION 7 — HEADER INSPECTION
#
# Kafka message headers are key-value pairs attached to records
# They don't affect routing or partitioning — purely metadata
#
# Common uses:
#   trace-id       — distributed tracing (Zipkin, Jaeger)
#   schema-version — schema evolution without breaking consumers
#   source-service — which microservice produced this message
#   produced-at    — timestamp injected by ProducerAuditInterceptor
# =============================================================================

# Consume and print all headers
docker exec -it kafka-1 kafka-console-consumer `
  --bootstrap-server $BROKERS --topic orders `
  --from-beginning `
  --property print.headers=true `
  --property print.key=true `
  --property print.timestamp=true `
  --property headers.separator="|"

# =============================================================================
# SECTION 8 — PARTITION REASSIGNMENT
#
# Use case: Adding a new broker — rebalance partitions to use it
# =============================================================================

# Generate a reassignment plan (moves partitions to include broker 4 if added)
docker exec -it kafka-1 kafka-reassign-partitions `
  --bootstrap-server $BROKERS `
  --topics-to-move-json-file /tmp/topics.json `
  --broker-list "1,2,3" `
  --generate

# Execute reassignment (after reviewing the plan)
# docker exec -it kafka-1 kafka-reassign-partitions `
#   --bootstrap-server $BROKERS `
#   --reassignment-json-file /tmp/reassignment.json `
#   --execute

# Verify reassignment progress
# docker exec -it kafka-1 kafka-reassign-partitions `
#   --bootstrap-server $BROKERS `
#   --reassignment-json-file /tmp/reassignment.json `
#   --verify
