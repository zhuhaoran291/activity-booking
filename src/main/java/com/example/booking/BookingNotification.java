package com.example.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BookingNotification {
    static final String QUEUE = "booking.notification";
    private static final Logger log = LoggerFactory.getLogger(BookingNotification.class);
    private final RabbitTemplate rabbit;

    public BookingNotification(RabbitTemplate rabbit) { this.rabbit = rabbit; }

    public record BookingCreated(long orderId, long userId) {}

    @TransactionalEventListener
    public void sendAfterCommit(BookingCreated event) {
        String message = "orderId=" + event.orderId() + ", userId=" + event.userId();
        try {
            rabbit.convertAndSend(QUEUE, message);
            log.info("已发送报名通知消息: {}", message);
        } catch (AmqpException e) {
            log.warn("报名已成功，但通知消息发送失败: {}", message, e);
        }
    }

    @RabbitListener(queues = QUEUE)
    public void receive(String message) {
        log.info("收到报名通知（模拟发送）: {}", message);
    }

    @Configuration
    static class QueueConfig {
        @Bean
        Queue bookingNotificationQueue() { return new Queue(QUEUE, true); }
    }
}
