package com.kafkaplayground.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaplayground.consumer.KafkaConsumerService;
import com.kafkaplayground.model.Order;
import com.kafkaplayground.producer.KafkaProducerService;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(KafkaController.class)
class KafkaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private KafkaProducerService producer;

    @MockBean
    private KafkaConsumerService consumer;

    // =========================================================================
    // ORDERS
    // =========================================================================

    @Test
    void sendOrder_shouldReturn200WithOrderId() throws Exception {
        Order order = new Order("o1", "u1", "item-A", 99.99, "PLACED");

        mockMvc.perform(post("/api/kafka/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"))
                .andExpect(jsonPath("$.orderId").value("o1"));

        verify(producer).sendMessage(eq("orders"), any(Order.class));
    }

    @Test
    void sendOrder_shouldGenerateOrderIdWhenMissing() throws Exception {
        Order order = new Order(null, "u1", "item-A", 99.99, "PLACED");

        mockMvc.perform(post("/api/kafka/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").isNotEmpty());
    }

    @Test
    void sendKeyedOrder_shouldUseUserIdAsKey() throws Exception {
        Order order = new Order("o2", "user-123", "item-B", 50.0, "PLACED");

        mockMvc.perform(post("/api/kafka/orders/keyed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("user-123"));

        verify(producer).sendWithKey(eq("orders"), eq("user-123"), any(Order.class));
    }

    @Test
    void sendToPartition_shouldCallProducerWithCorrectPartition() throws Exception {
        Order order = new Order("o3", "u3", "item-C", 75.0, "PLACED");

        mockMvc.perform(post("/api/kafka/orders/partition/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partition").value("2"));

        verify(producer).sendToPartition(eq("orders"), eq(2), anyString(), any(Order.class));
    }

    @Test
    void sendWithHeaders_shouldReturn200WithHeadersInfo() throws Exception {
        Order order = new Order("o4", "u4", "item-D", 120.0, "PLACED");

        mockMvc.perform(post("/api/kafka/orders/headers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"));

        verify(producer).sendWithHeaders(eq("orders"), anyString(), any(Order.class), anyMap());
    }

    @Test
    void sendSync_shouldReturnPartitionAndOffset() throws Exception {
        Order order = new Order("o5", "u5", "item-E", 200.0, "PLACED");
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("orders", 1), 42L, 0, 0, 0, 0);
        SendResult<String, Object> result = new SendResult<>(null, metadata);
        when(producer.sendSync(anyString(), anyString(), any())).thenReturn(result);

        mockMvc.perform(post("/api/kafka/orders/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partition").value("1"))
                .andExpect(jsonPath("$.offset").value("42"));
    }

    @Test
    void sendBatch_shouldSendCorrectNumberOfOrders() throws Exception {
        mockMvc.perform(post("/api/kafka/orders/batch")
                        .param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5));

        verify(producer, times(5)).sendWithKey(eq("orders"), anyString(), any(Order.class));
    }

    @Test
    void sendTransactional_shouldCallTransactionalProducer() throws Exception {
        Order order = new Order("o6", "u6", "item-F", 300.0, "PLACED");

        mockMvc.perform(post("/api/kafka/orders/transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("committed"))
                .andExpect(jsonPath("$.orderId").value("o6"));

        verify(producer).sendTransactional(any(Order.class));
    }

    // =========================================================================
    // PAYMENTS
    // =========================================================================

    @Test
    void sendPayment_shouldSendToPaymentsTopic() throws Exception {
        Map<String, Object> payment = Map.of("txnId", "t1", "amount", 500.0);

        mockMvc.perform(post("/api/kafka/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payment)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("payments"));

        verify(producer).sendMessage(eq("payments"), any());
    }

    // =========================================================================
    // DLQ
    // =========================================================================

    @Test
    void sendToDlq_shouldCallDlqProducer() throws Exception {
        Map<String, Object> payload = Map.of("orderId", "bad-order");

        mockMvc.perform(post("/api/kafka/dlq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("orders-dlq"));

        verify(producer).sendToDlq(eq("orders"), any(), anyString());
    }

    // =========================================================================
    // PROFILES (Compacted Topic + Tombstone)
    // =========================================================================

    @Test
    void sendProfile_shouldUseUserIdAsKey() throws Exception {
        Map<String, Object> profile = Map.of("userId", "u1", "name", "Alice", "tier", "gold");

        mockMvc.perform(post("/api/kafka/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("u1"));

        verify(producer).sendWithKey(eq("user-profiles"), eq("u1"), any());
    }

    @Test
    void deleteProfile_shouldSendTombstoneForKey() throws Exception {
        mockMvc.perform(delete("/api/kafka/profiles/user-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("tombstone sent"))
                .andExpect(jsonPath("$.key").value("user-123"));

        verify(producer).sendTombstone("user-profiles", "user-123");
    }

    // =========================================================================
    // STOCKS
    // =========================================================================

    @Test
    void sendStockPrice_shouldUseSymbolAsKey() throws Exception {
        Map<String, Object> price = Map.of("symbol", "AAPL", "price", 182.50);

        mockMvc.perform(post("/api/kafka/stocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(price)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"));

        verify(producer).sendWithKey(eq("stock-prices"), eq("AAPL"), any());
    }

    // =========================================================================
    // AUDIT EVENTS
    // =========================================================================

    @Test
    void sendAuditEvent_shouldSendToAuditTopic() throws Exception {
        Map<String, Object> event = Map.of("action", "USER_LOGIN", "userId", "u1");

        mockMvc.perform(post("/api/kafka/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("audit-events"))
                .andExpect(jsonPath("$.eventId").isNotEmpty());

        verify(producer).sendWithKey(eq("audit-events"), anyString(), any());
    }

    // =========================================================================
    // PAUSE / RESUME
    // =========================================================================

    @Test
    void pauseConsumer_shouldReturnPausedStatus() throws Exception {
        mockMvc.perform(post("/api/kafka/consumers/orders-listener/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paused"))
                .andExpect(jsonPath("$.listenerId").value("orders-listener"));

        verify(consumer).pauseConsumer("orders-listener");
    }

    @Test
    void resumeConsumer_shouldReturnResumedStatus() throws Exception {
        mockMvc.perform(post("/api/kafka/consumers/orders-listener/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("resumed"));

        verify(consumer).resumeConsumer("orders-listener");
    }

    @Test
    void consumerStatus_shouldReturnPausedFlag() throws Exception {
        when(consumer.isConsumerPaused("orders-listener")).thenReturn(true);

        mockMvc.perform(get("/api/kafka/consumers/orders-listener/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.listenerId").value("orders-listener"));
    }

    // =========================================================================
    // CONSUMER MONITORING
    // =========================================================================

    @Test
    void getReceivedMessages_shouldReturnConsumerMessages() throws Exception {
        when(consumer.getReceivedMessages()).thenReturn(List.of("orders:0:o1", "payments:1", "audit:evt-1:0"));

        mockMvc.perform(get("/api/kafka/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("orders:0:o1"))
                .andExpect(jsonPath("$[2]").value("audit:evt-1:0"));
    }

    @Test
    void clearMessages_shouldReturn200() throws Exception {
        mockMvc.perform(delete("/api/kafka/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cleared"));

        verify(consumer).clearMessages();
    }
}
