package com.example.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@AutoConfigureMockMvc
class BookingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BookingService service;
    @MockBean ActivityCache activityCache;
    @MockBean RabbitTemplate rabbit;
    @Autowired TransactionTemplate transaction;

    @Test
    void sendsNotificationOnlyAfterCommit() {
        long activityId = service.createActivity("消息测试", 2, BigDecimal.ONE).id();
        BookingService.Order committed = transaction.execute(status -> {
            BookingService.Order created = service.book(activityId, 123);
            verify(rabbit, never()).convertAndSend(anyString(), anyString());
            return created;
        });
        verify(rabbit).convertAndSend(BookingNotification.QUEUE,
                "orderId=" + committed.id() + ", userId=123");

        transaction.executeWithoutResult(status -> {
            service.book(activityId, 456);
            status.setRollbackOnly();
        });
        verify(rabbit, times(1)).convertAndSend(anyString(), anyString());
        assertThat(service.activity(activityId).remainingStock()).isEqualTo(1);
    }

    @Test
    void httpFlowAndCancellationRestoreStockOnlyOnce() throws Exception {
        String activityResponse = mvc.perform(post("/activities").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Java 分享会\",\"stock\":1,\"price\":10.00}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long activityId = json.readTree(activityResponse).get("id").asLong();
        String body = "{\"activityId\":" + activityId + ",\"userId\":1}";
        String orderResponse = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long orderId = json.readTree(orderResponse).get("id").asLong();
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(post("/orders/{id}/cancel", orderId)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/orders/{id}/cancel", orderId)).andExpect(status().isConflict());
        mvc.perform(get("/activities/{id}", activityId)).andExpect(jsonPath("$.remainingStock").value(1));

        String second = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long secondId = json.readTree(second).get("id").asLong();
        mvc.perform(post("/orders/{id}/pay", secondId)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(post("/orders/{id}/cancel", secondId)).andExpect(status().isConflict());
        mvc.perform(get("/orders").param("userId", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void concurrentBookingsDoNotOversell() throws Exception {
        long activityId = service.createActivity("并发报名", 10, BigDecimal.ONE).id();
        var pool = Executors.newFixedThreadPool(12);
        try {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 1; i <= 30; i++) {
                long userId = i;
                tasks.add(() -> {
                    try { service.book(activityId, userId); return true; }
                    catch (ResponseStatusException e) {
                        if (e.getStatusCode().value() != 409) throw e;
                        return false;
                    }
                });
            }
            int success = 0;
            for (Future<Boolean> result : pool.invokeAll(tasks)) if (result.get()) success++;
            assertThat(success).isEqualTo(10);
            assertThat(service.activity(activityId).remainingStock()).isZero();
        } finally { pool.shutdown(); }
    }
}
