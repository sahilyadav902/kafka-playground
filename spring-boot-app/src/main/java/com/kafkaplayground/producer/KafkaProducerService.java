package com.kafkaplayground.producer;

import com.kafkaplayground.model.Order;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class KafkaProducerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTemplate<String, Object> transactionalKafkaTemplate;

    public KafkaProducerService(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Qualifier("transactionalKafkaTemplate") KafkaTemplate<String, Object> transactionalKafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.transactionalKafkaTemplate = transactionalKafkaTemplate;
    }

    // Fire-and-forget: no key, Kafka round-robins across partitions
    public void sendMessage(String topic, Object message) {
        kafkaTemplate.send(topic, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send to {}: {}", topic, ex.getMessage());
                    } else {
                        log.info("Sent to {}[partition={}][offset={}]",
                                topic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    // Keyed message: same key always goes to same partition = ordering per key
    // Use case: all orders for user-123 go to partition 1, always in order
    public void sendWithKey(String topic, String key, Object message) {
        kafkaTemplate.send(topic, key, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send keyed message: {}", ex.getMessage());
                    } else {
                        log.info("Sent key={} to {}[partition={}]",
                                key, topic, result.getRecordMetadata().partition());
                    }
                });
    }

    // Send to specific partition — useful for testing partition behavior
    public void sendToPartition(String topic, int partition, String key, Object message) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, partition, key, message);
        kafkaTemplate.send(record);
        log.info("Sent to {}[partition={}] with key={}", topic, partition, key);
    }

    // Send with custom headers — metadata without polluting the payload
    // Use case: trace-id, source-service, schema-version
    public void sendWithHeaders(String topic, String key, Object message, Map<String, String> headers) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, message);
        headers.forEach((k, v) ->
                record.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8))));
        kafkaTemplate.send(record);
        log.info("Sent to {} with {} headers", topic, headers.size());
    }

    // Synchronous send — blocks until broker acknowledges (use sparingly, reduces throughput)
    public SendResult<String, Object> sendSync(String topic, String key, Object message) throws Exception {
        return kafkaTemplate.send(topic, key, message).get();
    }

    // Send to DLQ — called when message processing fails after all retries
    public void sendToDlq(String originalTopic, Object failedMessage, String errorReason) {
        String dlqTopic = originalTopic + "-dlq";
        Map<String, String> headers = Map.of(
                "original-topic", originalTopic,
                "error-reason", errorReason,
                "retry-count", "3"
        );
        sendWithHeaders(dlqTopic, null, failedMessage, headers);
        log.warn("Message sent to DLQ {}: {}", dlqTopic, errorReason);
    }

    // Transactional send — atomically writes to multiple topics in one transaction
    // Either ALL writes succeed and are visible, or NONE are (no partial state)
    // Use case: debit account AND publish payment event — must both succeed or both fail
    public void sendTransactional(Order order) {
        transactionalKafkaTemplate.executeInTransaction(ops -> {
            // Write 1: publish order event
            ops.send("orders", order.getUserId(), order);
            // Write 2: publish inventory deduction event atomically with the order
            ops.send("inventory-updates", order.getUserId(),
                    Map.of("orderId", order.getOrderId(), "item", order.getItem(), "action", "RESERVE"));
            log.info("Transactional send: order={} written to orders + inventory-updates atomically",
                    order.getOrderId());
            return true;
        });
    }

    // Tombstone: send a null-value message for a key to a compacted topic
    // During log compaction, Kafka deletes all records for this key
    // Use case: GDPR right-to-erasure — delete user profile from compacted topic
    public void sendTombstone(String topic, String key) {
        ProducerRecord<String, Object> tombstone = new ProducerRecord<>(topic, key, null);
        kafkaTemplate.send(tombstone)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send tombstone for key={}: {}", key, ex.getMessage());
                    } else {
                        log.info("Tombstone sent for key={} to {}[partition={}]",
                                key, topic, result.getRecordMetadata().partition());
                    }
                });
    }
}
