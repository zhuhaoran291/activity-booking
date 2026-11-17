package com.example.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ActivityCacheTest {
    @Test
    void cachesAndReadsActivityAsJson() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectMapper json = new ObjectMapper();
        ActivityCache cache = new ActivityCache(redis, json);
        var activity = new BookingService.Activity(1, "Java", 10, 9, new BigDecimal("10.00"));
        String value = json.writeValueAsString(activity);

        cache.put(activity);
        verify(values).set("activity:1", value, Duration.ofSeconds(30));
        when(values.get("activity:1")).thenReturn(value);
        assertThat(cache.get(1)).isEqualTo(activity);
    }

    @Test
    void evictsOnlyAfterCommit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ActivityCache cache = new ActivityCache(redis, new ObjectMapper());
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evictAfterCommit(1);
            TransactionSynchronization callback = TransactionSynchronizationManager.getSynchronizations().get(0);
            verify(redis, never()).delete("activity:1");
            callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(redis, never()).delete("activity:1");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evictAfterCommit(1);
            TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();
            verify(redis).delete("activity:1");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
