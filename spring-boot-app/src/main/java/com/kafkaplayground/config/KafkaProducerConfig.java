package com.kafkaplayground.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private Map<String, Object> baseProducerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        // Attach interceptor — logs every sent record (topic, partition, offset)
        props.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, ProducerAuditInterceptor.class.getName());
        return props;
    }

    // Standard non-transactional producer factory (used by default KafkaTemplate)
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        return new DefaultKafkaProducerFactory<>(baseProducerProps());
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // Transactional producer factory — each instance gets a unique transactional.id
    // Required for atomic multi-topic writes (exactly-once across topics)
    @Bean
    public ProducerFactory<String, Object> transactionalProducerFactory() {
        Map<String, Object> props = new HashMap<>(baseProducerProps());
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "kafka-playground-tx-");
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(props);
        factory.setTransactionIdPrefix("kafka-playground-tx-");
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> transactionalKafkaTemplate() {
        return new KafkaTemplate<>(transactionalProducerFactory());
    }

    // -------------------------------------------------------------------------
    // Producer Interceptor — runs before every send, after every ack/error
    // Use case: audit logging, metrics, header injection
    // -------------------------------------------------------------------------
    public static class ProducerAuditInterceptor implements ProducerInterceptor<String, Object> {
        private static final Logger log = LoggerFactory.getLogger(ProducerAuditInterceptor.class);

        @Override
        public ProducerRecord<String, Object> onSend(ProducerRecord<String, Object> record) {
            // Inject a timestamp header on every outgoing record automatically
            record.headers().add("produced-at", String.valueOf(System.currentTimeMillis()).getBytes());
            log.debug("Interceptor onSend: topic={} key={}", record.topic(), record.key());
            return record;
        }

        @Override
        public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
            if (exception != null) {
                log.warn("Interceptor onAck ERROR: topic={} error={}", metadata != null ? metadata.topic() : "unknown", exception.getMessage());
            }
        }

        @Override
        public void close() {}

        @Override
        public void configure(Map<String, ?> configs) {}
    }
}
