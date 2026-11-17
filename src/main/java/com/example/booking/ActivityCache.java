package com.example.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ActivityCache {
    private static final Logger log = LoggerFactory.getLogger(ActivityCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public ActivityCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public BookingService.Activity get(long id) {
        try {
            String value = redis.opsForValue().get(key(id));
            return value == null ? null : json.readValue(value, BookingService.Activity.class);
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("读取活动缓存失败，转查数据库: {}", id, e);
            return null;
        }
    }

    public void put(BookingService.Activity activity) {
        try {
            redis.opsForValue().set(key(activity.id()), json.writeValueAsString(activity), Duration.ofSeconds(30));
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("写入活动缓存失败: {}", activity.id(), e);
        }
    }

    public void evictAfterCommit(long id) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redis.delete(key(id));
                } catch (DataAccessException e) {
                    log.warn("清除活动缓存失败，缓存将等待过期: {}", id, e);
                }
            }
        });
    }

    private String key(long id) { return "activity:" + id; }
}
