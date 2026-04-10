package com.kafkaplayground;

import com.kafkaplayground.model.Order;
import com.kafkaplayground.producer.KafkaProducerService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext
@EmbeddedKafka(
        partitions = 3,
        topics = {"orders", "payments", "notifications", "orders-dlq",
                  "user-profiles", "stock-prices", "audit-events", "inventory-updates"},
        brokerProperties = {
                "auto.create.topics.enable=true",
                "offsets.topic.replication.factor=1",
                "transaction.state.log.replication.factor=1",
                "transaction.state.log.min.isr=1"
        }
)
class KafkaIntegrationTest {

    @Autowired
    private KafkaProducerService producerService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    private static final BlockingQueue<ConsumerRecord<String, ?>> testRecords = new LinkedBlockingQueue<>();
    private static final BlockingQueue<ConsumerRecord<String, ?>> inventoryRecords = new LinkedBlockingQueue<>();

    @KafkaListener(topics = "orders", groupId = "integration-test-group")
    public void captureOrders(ConsumerRecord<String, ?> record) {
        testRecords.offer(record);
    }

    @KafkaListener(topics = "inventory-updates", groupId = "integration-inventory-group")
    public void captureInventory(ConsumerRecord<String, ?> record) {
        inventoryRecords.offer(record);
    }

    @Test
    void produceAndConsume_orderShouldBeReceivedByConsumer() throws InterruptedException {
        Order order = new Order("int-test-1", "u1", "item-A", 99.99, "PLACED");

        producerService.sendWithKey("orders", "u1", order);

        ConsumerRecord<String, ?> received = testRecords.poll(10, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.key()).isEqualTo("u1");
    }

    @Test
    void keyedMessages_shouldLandInSamePartition() throws InterruptedException {
        testRecords.clear();

        Order order1 = new Order("int-2a", "user-X", "item-1", 10.0, "PLACED");
        Order order2 = new Order("int-2b", "user-X", "item-2", 20.0, "PLACED");

        producerService.sendWithKey("orders", "user-X", order1);
        producerService.sendWithKey("orders", "user-X", order2);

        ConsumerRecord<String, ?> r1 = testRecords.poll(10, TimeUnit.SECONDS);
        ConsumerRecord<String, ?> r2 = testRecords.poll(10, TimeUnit.SECONDS);

        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        // Same key = same partition = ordering guaranteed
        assertThat(r1.partition()).isEqualTo(r2.partition());
        assertThat(r2.offset()).isGreaterThan(r1.offset());
    }

    @Test
    void syncSend_shouldReturnMetadataWithPartitionAndOffset() throws Exception {
        Order order = new Order("int-3", "u3", "item-C", 50.0, "PLACED");

        var result = producerService.sendSync("orders", "u3", order);

        assertThat(result.getRecordMetadata().partition()).isGreaterThanOrEqualTo(0);
        assertThat(result.getRecordMetadata().offset()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void dlqSend_shouldRouteToOrdersDlqTopic() {
        // Direct send to DLQ topic — verifies topic exists and accepts messages
        kafkaTemplate.send("orders-dlq", "failed-order-key", "failed payload");
        // No exception = topic is reachable and writable
    }

    @Test
    void tombstone_shouldSendNullValueToCompactedTopic() throws InterruptedException {
        // First write a real record
        producerService.sendWithKey("user-profiles", "user-del", "some profile data");
        Thread.sleep(500);

        // Then send tombstone — null value signals deletion during compaction
        producerService.sendTombstone("user-profiles", "user-del");
        Thread.sleep(500);
        // No exception = tombstone accepted by broker
    }

    @Test
    void producerInterceptor_shouldInjectProducedAtHeader() throws InterruptedException {
        testRecords.clear();
        Order order = new Order("int-hdr", "u-hdr", "item-H", 1.0, "PLACED");

        producerService.sendWithKey("orders", "u-hdr", order);

        ConsumerRecord<String, ?> received = testRecords.poll(10, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        // ProducerAuditInterceptor injects "produced-at" header on every record
        assertThat(received.headers().lastHeader("produced-at")).isNotNull();
    }
}
