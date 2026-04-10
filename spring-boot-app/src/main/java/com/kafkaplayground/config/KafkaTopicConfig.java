package com.kafkaplayground.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // 3 partitions, 3 replicas — production-grade HA
    @Bean
    public NewTopic ordersTopic() {
        return TopicBuilder.name("orders")
                .partitions(3)
                .replicas(3)
                .build();
    }

    // 6 partitions — higher throughput for payments
    @Bean
    public NewTopic paymentsTopic() {
        return TopicBuilder.name("payments")
                .partitions(6)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic notificationsTopic() {
        return TopicBuilder.name("notifications")
                .partitions(2)
                .replicas(3)
                .build();
    }

    // Dead Letter Queue — receives messages that failed all retries
    @Bean
    public NewTopic ordersDlqTopic() {
        return TopicBuilder.name("orders-dlq")
                .partitions(3)
                .replicas(3)
                .build();
    }

    // Compacted topic — only latest value per key is retained
    @Bean
    public NewTopic userProfilesTopic() {
        return TopicBuilder.name("user-profiles")
                .partitions(3)
                .replicas(3)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
                .build();
    }

    @Bean
    public NewTopic stockPricesTopic() {
        return TopicBuilder.name("stock-prices")
                .partitions(6)
                .replicas(3)
                .build();
    }

    // Audit events — long retention (90 days), used for compliance replay
    @Bean
    public NewTopic auditEventsTopic() {
        return TopicBuilder.name("audit-events")
                .partitions(3)
                .replicas(3)
                .config(TopicConfig.RETENTION_MS_CONFIG, "7776000000")
                .build();
    }

    // Inventory updates — used for transactions demo (atomic multi-topic produce)
    @Bean
    public NewTopic inventoryTopic() {
        return TopicBuilder.name("inventory-updates")
                .partitions(3)
                .replicas(3)
                .build();
    }
}
