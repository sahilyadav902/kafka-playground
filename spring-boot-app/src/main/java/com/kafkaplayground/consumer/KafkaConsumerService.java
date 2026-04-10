package com.kafkaplayground.consumer;

import com.kafkaplayground.model.Order;
import com.kafkaplayground.producer.KafkaProducerService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class KafkaConsumerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerService.class);

    private final KafkaProducerService producerService;
    // Registry lets us programmatically pause/resume listener containers by id
    private final KafkaListenerEndpointRegistry listenerRegistry;

    // In-memory store for demo — replace with DB in production
    private final List<String> receivedMessages = new CopyOnWriteArrayList<>();

    public KafkaConsumerService(KafkaProducerService producerService,
                                KafkaListenerEndpointRegistry listenerRegistry) {
        this.producerService = producerService;
        this.listenerRegistry = listenerRegistry;
    }

    // Basic consumer with manual acknowledgment (at-least-once delivery)
    // Manual ack = offset committed only after successful processing
    // If app crashes before ack, message is redelivered — no data loss
    @KafkaListener(id = "orders-listener", topics = "orders", groupId = "order-processor",
                   containerFactory = "kafkaListenerContainerFactory")
    public void consumeOrder(ConsumerRecord<String, Order> record, Acknowledgment ack) {
        try {
            Order order = record.value();
            log.info("Processing order: key={} partition={} offset={} value={}",
                    record.key(), record.partition(), record.offset(), order);

            // Read the produced-at header injected by ProducerAuditInterceptor
            String producedAt = getHeader(record, "produced-at");
            log.debug("Message produced-at header: {}", producedAt);

            processOrder(order);
            receivedMessages.add("orders:" + record.offset() + ":" + order.getOrderId());

            ack.acknowledge(); // commit offset only after successful processing
        } catch (Exception e) {
            log.error("Failed to process order at offset {}: {}", record.offset(), e.getMessage());
            // Do NOT ack — message will be redelivered
        }
    }

    // Auto-retry with exponential backoff + automatic DLQ routing
    // Retries: attempt 1 after 1s, attempt 2 after 2s, attempt 3 after 4s → DLQ
    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2),
            dltTopicSuffix = "-dlq"
    )
    @KafkaListener(id = "payments-listener", topics = "payments", groupId = "payment-processor")
    public void consumePayment(ConsumerRecord<String, Object> record) {
        log.info("Processing payment: partition={} offset={}", record.partition(), record.offset());

        // Simulate intermittent failure for demo
        if (record.value() != null && record.value().toString().contains("FAIL")) {
            throw new RuntimeException("Payment gateway unavailable — will retry");
        }

        receivedMessages.add("payments:" + record.offset());
        log.info("Payment processed successfully");
    }

    // Batch consumer — processes multiple messages per poll for higher throughput
    // Use case: bulk DB inserts, batch API calls
    @KafkaListener(id = "notifications-listener", topics = "notifications",
                   groupId = "notification-processor",
                   containerFactory = "batchKafkaListenerContainerFactory")
    public void consumeNotificationsBatch(List<ConsumerRecord<String, Object>> records, Acknowledgment ack) {
        log.info("Processing batch of {} notifications", records.size());
        records.forEach(r -> {
            log.info("Notification: partition={} offset={} value={}", r.partition(), r.offset(), r.value());
            receivedMessages.add("notifications:" + r.offset());
        });
        ack.acknowledge(); // commit entire batch at once
    }

    // DLQ consumer — ops team monitors and reprocesses failed messages
    @KafkaListener(id = "dlq-listener", topics = "orders-dlq", groupId = "dlq-monitor")
    public void consumeDlq(ConsumerRecord<String, Object> record) {
        String originalTopic = getHeader(record, "original-topic");
        String errorReason = getHeader(record, "error-reason");
        log.warn("DLQ message from topic={} error={} value={}", originalTopic, errorReason, record.value());
        receivedMessages.add("dlq:" + record.offset() + ":" + errorReason);
    }

    // Stock prices consumer — demonstrates ordering guarantee per key (stock symbol)
    @KafkaListener(id = "stocks-listener", topics = "stock-prices", groupId = "stock-processor")
    public void consumeStockPrice(ConsumerRecord<String, Object> record) {
        log.info("Stock update: symbol={} partition={} offset={} price={}",
                record.key(), record.partition(), record.offset(), record.value());
        receivedMessages.add("stock:" + record.key() + ":" + record.offset());
    }

    // Audit events consumer — long-retention topic, used for compliance/replay
    @KafkaListener(id = "audit-listener", topics = "audit-events", groupId = "audit-processor")
    public void consumeAuditEvent(ConsumerRecord<String, Object> record) {
        log.info("Audit event: key={} partition={} offset={} value={}",
                record.key(), record.partition(), record.offset(), record.value());
        receivedMessages.add("audit:" + record.key() + ":" + record.offset());
    }

    // Inventory consumer — receives atomic writes from transactional producer
    @KafkaListener(id = "inventory-listener", topics = "inventory-updates", groupId = "inventory-processor")
    public void consumeInventoryUpdate(ConsumerRecord<String, Object> record) {
        log.info("Inventory update: key={} partition={} offset={} value={}",
                record.key(), record.partition(), record.offset(), record.value());
        receivedMessages.add("inventory:" + record.key() + ":" + record.offset());
    }

    // -------------------------------------------------------------------------
    // PAUSE / RESUME — programmatic consumer control
    // Use case: downstream DB is overloaded, pause consumption to apply backpressure
    //           Resume once DB recovers. Kafka retains messages — nothing is lost.
    // -------------------------------------------------------------------------
    public void pauseConsumer(String listenerId) {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(listenerId);
        if (container != null && container.isRunning()) {
            container.pause();
            log.info("Consumer paused: {}", listenerId);
        }
    }

    public void resumeConsumer(String listenerId) {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(listenerId);
        if (container != null) {
            container.resume();
            log.info("Consumer resumed: {}", listenerId);
        }
    }

    public boolean isConsumerPaused(String listenerId) {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(listenerId);
        return container != null && container.isContainerPaused();
    }

    public List<String> getReceivedMessages() {
        return new ArrayList<>(receivedMessages);
    }

    public void clearMessages() {
        receivedMessages.clear();
    }

    private void processOrder(Order order) {
        if (order == null || order.getOrderId() == null) {
            throw new IllegalArgumentException("Invalid order");
        }
        log.info("Order {} processed for user {}", order.getOrderId(), order.getUserId());
    }

    private String getHeader(ConsumerRecord<?, ?> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : "unknown";
    }
}
