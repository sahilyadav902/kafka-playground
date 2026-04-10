package com.kafkaplayground.producer;

import com.kafkaplayground.model.Order;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaProducerServiceTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private KafkaTemplate<String, Object> transactionalKafkaTemplate;

    private KafkaProducerService producerService;

    @BeforeEach
    void setUp() {
        producerService = new KafkaProducerService(kafkaTemplate, transactionalKafkaTemplate);
    }

    @Test
    void sendMessage_shouldCallKafkaTemplateWithCorrectTopicAndPayload() {
        Order order = new Order("o1", "u1", "item-A", 99.99, "PLACED");
        when(kafkaTemplate.send(anyString(), any())).thenReturn(CompletableFuture.completedFuture(mockSendResult("orders", 0, 0)));

        producerService.sendMessage("orders", order);

        verify(kafkaTemplate).send("orders", order);
    }

    @Test
    void sendWithKey_shouldRouteToCorrectTopicWithKey() {
        Order order = new Order("o1", "user-123", "item-A", 99.99, "PLACED");
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(mockSendResult("orders", 1, 5)));

        producerService.sendWithKey("orders", "user-123", order);

        verify(kafkaTemplate).send("orders", "user-123", order);
    }

    @Test
    void sendToPartition_shouldSendToExactPartition() {
        Order order = new Order("o2", "u2", "item-B", 50.0, "PLACED");
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mockSendResult("orders", 2, 10)));

        producerService.sendToPartition("orders", 2, "u2", order);

        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().partition()).isEqualTo(2);
        assertThat(captor.getValue().key()).isEqualTo("u2");
    }

    @Test
    void sendWithHeaders_shouldAttachAllHeaders() {
        Order order = new Order("o3", "u3", "item-C", 75.0, "PLACED");
        Map<String, String> headers = Map.of("trace-id", "abc-123", "source", "test");
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mockSendResult("orders", 0, 1)));

        producerService.sendWithHeaders("orders", "u3", order, headers);

        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<?, ?> record = captor.getValue();
        assertThat(record.headers().lastHeader("trace-id")).isNotNull();
        assertThat(record.headers().lastHeader("source")).isNotNull();
    }

    @Test
    void sendToDlq_shouldSendToTopicWithDlqSuffix() {
        Order order = new Order("o4", "u4", "item-D", 10.0, "FAILED");
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mockSendResult("orders-dlq", 0, 0)));

        producerService.sendToDlq("orders", order, "Processing failed");

        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("orders-dlq");
    }

    @Test
    void sendToDlq_shouldAttachErrorReasonHeader() {
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mockSendResult("orders-dlq", 0, 0)));

        producerService.sendToDlq("orders", Map.of("id", "bad"), "Validation failed");

        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().headers().lastHeader("error-reason")).isNotNull();
        assertThat(captor.getValue().headers().lastHeader("original-topic")).isNotNull();
    }

    @Test
    void sendSync_shouldReturnSendResultWithMetadata() throws Exception {
        Order order = new Order("o5", "u5", "item-E", 200.0, "PLACED");
        SendResult<String, Object> expectedResult = mockSendResult("orders", 1, 42);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(expectedResult));

        SendResult<String, Object> result = producerService.sendSync("orders", "u5", order);

        assertThat(result.getRecordMetadata().partition()).isEqualTo(1);
        assertThat(result.getRecordMetadata().offset()).isEqualTo(42);
    }

    @Test
    void sendMessage_shouldHandleFailureGracefully() {
        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Broker unavailable"));
        when(kafkaTemplate.send(anyString(), any())).thenReturn(failedFuture);

        // Should not throw — failure is logged, not propagated
        producerService.sendMessage("orders", new Order());
        verify(kafkaTemplate).send("orders", new Order());
    }

    @Test
    void sendTombstone_shouldSendNullValueForKey() {
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mockSendResult("user-profiles", 0, 5)));

        producerService.sendTombstone("user-profiles", "user-123");

        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("user-profiles");
        assertThat(captor.getValue().key()).isEqualTo("user-123");
        assertThat(captor.getValue().value()).isNull(); // tombstone = null value
    }

    @Test
    void sendTransactional_shouldExecuteInTransaction() {
        Order order = new Order("o6", "u6", "item-F", 300.0, "PLACED");
        when(transactionalKafkaTemplate.executeInTransaction(any())).thenReturn(true);

        producerService.sendTransactional(order);

        verify(transactionalKafkaTemplate).executeInTransaction(any());
    }

    private SendResult<String, Object> mockSendResult(String topic, int partition, long offset) {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(topic, partition), offset, 0, 0, 0, 0);
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, "key", "value");
        return new SendResult<>(record, metadata);
    }
}
