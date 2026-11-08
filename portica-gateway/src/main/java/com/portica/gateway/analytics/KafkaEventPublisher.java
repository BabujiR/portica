package com.portica.gateway.analytics;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes to Kafka from one background thread. Request threads only {@code offer} to a bounded
 * queue (never blocking); the sender thread serializes and hands records to the Kafka producer, which
 * batches and retries on its own. When Kafka is down or slow and the queue is full, events are dropped
 * and counted, and a warning is logged at most once a minute.
 */
public class KafkaEventPublisher implements EventPublisher, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private record Pending(String topic, String key, Map<String, Object> event) {
    }

    private final AnalyticsProperties properties;
    private final BlockingQueue<Pending> queue;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile long lastWarning;
    private volatile boolean running;
    private Thread sender;
    private KafkaProducer<String, String> producer;

    public KafkaEventPublisher(AnalyticsProperties properties) {
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
    }

    @Override
    public void publish(String topic, String key, Map<String, Object> event) {
        if (!running || !queue.offer(new Pending(topic, key, event))) {
            dropped.incrementAndGet();
            warnRateLimited();
        }
    }

    /** Events dropped because the queue was full (or the publisher stopped). */
    public long droppedCount() {
        return dropped.get();
    }

    /** Events Kafka didn't accept (broker down past the delivery timeout, missing topic ...). */
    public long failedCount() {
        return failed.get();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "portica-gateway-" + properties.gatewayId());
        config.put(ProducerConfig.ACKS_CONFIG, "1");
        config.put(ProducerConfig.LINGER_MS_CONFIG, "50");
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");          // only the sender thread can block
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "60000");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false");
        producer = new KafkaProducer<>(config);
        running = true;
        sender = Thread.ofPlatform().name("portica-analytics-publisher").daemon(true).start(this::drain);
        log.info("Publishing analytics to Kafka at {} (gateway {})", properties.bootstrapServers(), properties.gatewayId());
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            Pending next;
            try {
                next = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (next == null) {
                continue;
            }
            try {
                String value = json.writeValueAsString(next.event());
                producer.send(new ProducerRecord<>(next.topic(), next.key(), value), (metadata, error) -> {
                    if (error != null) {
                        failed.incrementAndGet();
                        warnRateLimited();
                    }
                });
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                warnRateLimited();
            }
        }
    }

    private void warnRateLimited() {
        long now = System.currentTimeMillis();
        if (now - lastWarning > 60_000) {
            lastWarning = now;
            log.warn("Analytics events are being lost (dropped {}, failed {}); is Kafka reachable at {}?",
                    dropped.get(), failed.get(), properties.bootstrapServers());
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            if (sender != null) {
                sender.join(Duration.ofSeconds(5));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        producer.close(Duration.ofSeconds(5));
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
