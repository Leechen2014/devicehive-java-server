package com.devicehive.shim.kafka.client;

import com.devicehive.shim.api.Response;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;

public class ResponseConsumerWorker implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(ResponseConsumerWorker.class);

    private String topic;
    private RequestResponseMatcher responseMatcher;
    private KafkaConsumer<String, Response> consumer;
    private CountDownLatch startupLatch;

    public ResponseConsumerWorker(String topic, RequestResponseMatcher responseMatcher,
                                  KafkaConsumer<String, Response> consumer, CountDownLatch startupLatch) {
        this.topic = topic;
        this.responseMatcher = responseMatcher;
        this.consumer = consumer;
        this.startupLatch = startupLatch;
    }

    @Override
    public void run() {
        try {
            consumer.subscribe(Collections.singletonList(topic));
            startupLatch.countDown();
            while (!Thread.currentThread().isInterrupted()) {
                ConsumerRecords<String, Response> records = consumer.poll(Long.MAX_VALUE);
                records.forEach(record -> {
                    logger.trace("Topic {}, partition {}, offset {}", record.topic(), record.partition(), record.offset());
                    responseMatcher.offerResponse(record.value());
                });
            }
        } catch (WakeupException e) {
            logger.warn("Response Consumer thread is shutting down");
        } finally {
            consumer.close();
        }
    }

    public void shutdown() {
        consumer.wakeup();
    }
}