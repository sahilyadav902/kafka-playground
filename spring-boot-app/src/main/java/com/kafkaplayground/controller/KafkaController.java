package com.kafkaplayground.controller;

import com.kafkaplayground.consumer.KafkaConsumerService;
import com.kafkaplayground.model.Order;
import com.kafkaplayground.producer.KafkaProducerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/kafka")
public class KafkaController {

    private final KafkaProducerService producer;
    private final KafkaConsumerService consumer;

    public KafkaController(KafkaProducerService producer, KafkaConsumerService consumer) {
        this.producer = producer;
        this.consumer = consumer;
    }

    // =========================================================================
    // ORDERS
    // =========================================================================

    // POST /api/kafka/orders
    // Sends an order without a key — Kafka round-robins across partitions
    @PostMapping("/orders")
    public ResponseEntity<Map<String, String>> sendOrder(@RequestBody Order order) {
        if (order.getOrderId() == null) order.setOrderId(UUID.randomUUID().toString());
        producer.sendMessage("orders", order);
        return ResponseEntity.ok(Map.of("status", "sent", "orderId", order.getOrderId()));
    }

    // POST /api/kafka/orders/keyed
    // Sends with userId as key — guarantees ordering per user across retries/restarts
    @PostMapping("/orders/keyed")
    public ResponseEntity<Map<String, String>> sendKeyedOrder(@RequestBody Order order) {
        if (order.getOrderId() == null) order.setOrderId(UUID.randomUUID().toString());
        producer.sendWithKey("orders", order.getUserId(), order);
        return ResponseEntity.ok(Map.of("status", "sent", "key", order.getUserId(),
                "orderId", order.getOrderId()));
    }

    // POST /api/kafka/orders/partition/{partitionId}
    // Sends to a specific partition — useful for testing partition assignment
    @PostMapping("/orders/partition/{partitionId}")
    public ResponseEntity<Map<String, String>> sendToPartition(
            @PathVariable int partitionId, @RequestBody Order order) {
        if (order.getOrderId() == null) order.setOrderId(UUID.randomUUID().toString());
        producer.sendToPartition("orders", partitionId, order.getUserId(), order);
        return ResponseEntity.ok(Map.of("status", "sent", "partition", String.valueOf(partitionId)));
    }

    // POST /api/kafka/orders/headers
    // Sends with custom headers — trace-id, source-service for distributed tracing
    @PostMapping("/orders/headers")
    public ResponseEntity<Map<String, String>> sendWithHeaders(@RequestBody Order order) {
        if (order.getOrderId() == null) order.setOrderId(UUID.randomUUID().toString());
        Map<String, String> headers = Map.of(
                "trace-id", UUID.randomUUID().toString(),
                "source-service", "order-api",
                "schema-version", "v2"
        );
        producer.sendWithHeaders("orders", order.getUserId(), order, headers);
        return ResponseEntity.ok(Map.of("status", "sent", "headers", headers.toString()));
    }

    // POST /api/kafka/orders/sync
    // Synchronous send — blocks until broker confirms. Lower throughput but immediate feedback.
    @PostMapping("/orders/sync")
    public ResponseEntity<Map<String, String>> sendSync(@RequestBody Order order) throws Exception {
        if (order.getOrderId() == null) order.setOrderId(UUID.randomUUID().toString());
        var result = producer.sendSync("orders", order.getUserId(), order);
        return ResponseEntity.ok(Map.of(
                "status", "confirmed",
                "partition", String.valueOf(result.getRecordMetadata().partition()),
                "offset", String.valueOf(result.getRecordMetadata().offset())
        ));
    }

    // POST /api/kafka/orders/batch?count=N
    // Sends multiple orders at once — tests producer throughput
    @PostMapping("/orders/batch")
    public ResponseEntity<Map<String, Object>> sendBatch(@RequestParam(defaultValue = "10") int count) {
        for (int i = 0; i < count; i++) {
            Order order = new Order(
                    UUID.randomUUID().toString(),
                    "user-" + (i % 5),
                    "item-" + i,
                    10.0 * (i + 1),
                    "PLACED"
            );
            producer.sendWithKey("orders", order.getUserId(), order);
        }
        return ResponseEntity.ok(Map.of("status", "sent", "count", count));
    }

    // POST /api/kafka/orders/transactional
    // Atomically writes to orders + inventory-updates in a single Kafka transaction
    // Either both writes succeed and become visible, or neither does
    @PostMapping("/orders/transactional")
    public ResponseEntity<Map<String, String>> sendTransactional(@RequestBody Order order) {
        if (order.getOrderId() == null) order.setOrderId(UUID.randomUUID().toString());
        producer.sendTransactional(order);
        return ResponseEntity.ok(Map.of(
                "status", "committed",
                "orderId", order.getOrderId(),
                "note", "Atomically written to orders + inventory-updates"
        ));
    }

    // =========================================================================
    // PAYMENTS
    // =========================================================================

    // POST /api/kafka/payments
    // Sends a payment event. If body contains "FAIL", triggers retry + DLQ flow
    @PostMapping("/payments")
    public ResponseEntity<Map<String, String>> sendPayment(@RequestBody Map<String, Object> payload) {
        producer.sendMessage("payments", payload);
        return ResponseEntity.ok(Map.of("status", "sent", "topic", "payments"));
    }

    // =========================================================================
    // NOTIFICATIONS
    // =========================================================================

    // POST /api/kafka/notifications
    // Sends to notifications topic — consumed in batch by notification-processor group
    @PostMapping("/notifications")
    public ResponseEntity<Map<String, String>> sendNotification(@RequestBody Map<String, String> payload) {
        producer.sendMessage("notifications", payload);
        return ResponseEntity.ok(Map.of("status", "sent", "topic", "notifications"));
    }

    // =========================================================================
    // DLQ
    // =========================================================================

    // POST /api/kafka/dlq
    // Manually send a message to DLQ — simulates exhausted retries
    @PostMapping("/dlq")
    public ResponseEntity<Map<String, String>> sendToDlq(@RequestBody Map<String, Object> payload) {
        producer.sendToDlq("orders", payload, "Manual DLQ test");
        return ResponseEntity.ok(Map.of("status", "sent to DLQ", "topic", "orders-dlq"));
    }

    // =========================================================================
    // USER PROFILES (Compacted Topic)
    // =========================================================================

    // POST /api/kafka/profiles
    // Sends to compacted topic — only latest value per userId key is retained
    @PostMapping("/profiles")
    public ResponseEntity<Map<String, String>> sendProfile(@RequestBody Map<String, Object> profile) {
        String userId = (String) profile.get("userId");
        producer.sendWithKey("user-profiles", userId, profile);
        return ResponseEntity.ok(Map.of("status", "sent", "key", userId));
    }

    // DELETE /api/kafka/profiles/{userId}
    // Sends a tombstone (null value) for the key — triggers deletion during compaction
    // Use case: GDPR right-to-erasure
    @DeleteMapping("/profiles/{userId}")
    public ResponseEntity<Map<String, String>> deleteProfile(@PathVariable String userId) {
        producer.sendTombstone("user-profiles", userId);
        return ResponseEntity.ok(Map.of(
                "status", "tombstone sent",
                "key", userId,
                "note", "Record will be deleted during next log compaction"
        ));
    }

    // =========================================================================
    // STOCK PRICES (Ordering Demo)
    // =========================================================================

    // POST /api/kafka/stocks
    // Key = stock symbol → all updates for same symbol go to same partition = ordered
    @PostMapping("/stocks")
    public ResponseEntity<Map<String, String>> sendStockPrice(@RequestBody Map<String, Object> price) {
        String symbol = (String) price.get("symbol");
        producer.sendWithKey("stock-prices", symbol, price);
        return ResponseEntity.ok(Map.of("status", "sent", "symbol", symbol));
    }

    // =========================================================================
    // AUDIT EVENTS (Long-retention Topic)
    // =========================================================================

    // POST /api/kafka/audit
    // Sends to audit-events topic (90-day retention) — compliance, replay, forensics
    @PostMapping("/audit")
    public ResponseEntity<Map<String, String>> sendAuditEvent(@RequestBody Map<String, Object> event) {
        String eventId = UUID.randomUUID().toString();
        producer.sendWithKey("audit-events", eventId, event);
        return ResponseEntity.ok(Map.of("status", "sent", "eventId", eventId, "topic", "audit-events"));
    }

    // =========================================================================
    // CONSUMER PAUSE / RESUME
    // =========================================================================

    // POST /api/kafka/consumers/{listenerId}/pause
    // Pauses a consumer — Kafka retains messages, no data loss
    // Use case: downstream DB overloaded, pause to apply backpressure
    @PostMapping("/consumers/{listenerId}/pause")
    public ResponseEntity<Map<String, String>> pauseConsumer(@PathVariable String listenerId) {
        consumer.pauseConsumer(listenerId);
        return ResponseEntity.ok(Map.of("status", "paused", "listenerId", listenerId));
    }

    // POST /api/kafka/consumers/{listenerId}/resume
    // Resumes a paused consumer — picks up from where it left off
    @PostMapping("/consumers/{listenerId}/resume")
    public ResponseEntity<Map<String, String>> resumeConsumer(@PathVariable String listenerId) {
        consumer.resumeConsumer(listenerId);
        return ResponseEntity.ok(Map.of("status", "resumed", "listenerId", listenerId));
    }

    // GET /api/kafka/consumers/{listenerId}/status
    // Returns whether a consumer is currently paused
    @GetMapping("/consumers/{listenerId}/status")
    public ResponseEntity<Map<String, Object>> consumerStatus(@PathVariable String listenerId) {
        boolean paused = consumer.isConsumerPaused(listenerId);
        return ResponseEntity.ok(Map.of("listenerId", listenerId, "paused", paused));
    }

    // =========================================================================
    // CONSUMER MONITORING
    // =========================================================================

    // GET /api/kafka/messages
    // Returns all messages received by consumers in this app instance
    @GetMapping("/messages")
    public ResponseEntity<List<String>> getReceivedMessages() {
        return ResponseEntity.ok(consumer.getReceivedMessages());
    }

    // DELETE /api/kafka/messages
    // Clears the in-memory received messages list
    @DeleteMapping("/messages")
    public ResponseEntity<Map<String, String>> clearMessages() {
        consumer.clearMessages();
        return ResponseEntity.ok(Map.of("status", "cleared"));
    }
}
