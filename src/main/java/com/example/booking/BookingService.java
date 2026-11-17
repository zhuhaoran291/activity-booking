package com.example.booking;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BookingService {
    private final JdbcTemplate jdbc;
    private final ActivityCache activityCache;
    private final ApplicationEventPublisher events;

    public BookingService(JdbcTemplate jdbc, ActivityCache activityCache, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.activityCache = activityCache;
        this.events = events;
    }

    public record Activity(long id, String title, int totalStock, int remainingStock, BigDecimal price) {}
    public record Order(long id, long activityId, long userId, String status, LocalDateTime createdAt) {}

    public Activity createActivity(String title, int stock, BigDecimal price) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO activity(title, total_stock, remaining_stock, price) VALUES (?, ?, ?, ?)", new String[]{"id"});
            ps.setString(1, title);
            ps.setInt(2, stock);
            ps.setInt(3, stock);
            ps.setBigDecimal(4, price);
            return ps;
        }, key);
        return activity(key.getKey().longValue());
    }

    public List<Activity> activities() {
        return jdbc.query("SELECT id, title, total_stock, remaining_stock, price FROM activity ORDER BY id",
                (rs, n) -> new Activity(rs.getLong("id"), rs.getString("title"), rs.getInt("total_stock"), rs.getInt("remaining_stock"), rs.getBigDecimal("price")));
    }

    public Activity activity(long id) {
        Activity cached = activityCache.get(id);
        if (cached != null) return cached;
        Activity activity = jdbc.query("SELECT id, title, total_stock, remaining_stock, price FROM activity WHERE id = ?",
                (rs, n) -> new Activity(rs.getLong("id"), rs.getString("title"), rs.getInt("total_stock"), rs.getInt("remaining_stock"), rs.getBigDecimal("price")), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在"));
        activityCache.put(activity);
        return activity;
    }

    @Transactional
    public Order book(long activityId, long userId) {
        // 条件更新让检查库存和扣减成为同一条原子 SQL。
        int updated = jdbc.update("UPDATE activity SET remaining_stock = remaining_stock - 1 WHERE id = ? AND remaining_stock > 0", activityId);
        if (updated == 0) {
            activity(activityId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "活动名额已满");
        }
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO booking_order(activity_id, user_id, status) VALUES (?, ?, 'PENDING')", new String[]{"id"});
            ps.setLong(1, activityId);
            ps.setLong(2, userId);
            return ps;
        }, key);
        activityCache.evictAfterCommit(activityId);
        Order created = order(key.getKey().longValue());
        events.publishEvent(new BookingNotification.BookingCreated(created.id(), created.userId()));
        return created;
    }

    public Order order(long id) {
        return jdbc.query("SELECT id, activity_id, user_id, status, created_at FROM booking_order WHERE id = ?",
                (rs, n) -> new Order(rs.getLong("id"), rs.getLong("activity_id"), rs.getLong("user_id"), rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime()), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
    }

    public List<Order> ordersForUser(long userId) {
        return jdbc.query("SELECT id, activity_id, user_id, status, created_at FROM booking_order WHERE user_id = ? ORDER BY id DESC",
                (rs, n) -> new Order(rs.getLong("id"), rs.getLong("activity_id"), rs.getLong("user_id"), rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime()), userId);
    }

    @Transactional
    public Order pay(long id) {
        if (jdbc.update("UPDATE booking_order SET status = 'PAID' WHERE id = ? AND status = 'PENDING'", id) == 0) {
            order(id);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有待支付订单可以支付");
        }
        return order(id);
    }

    @Transactional
    public Order cancel(long id) {
        if (jdbc.update("UPDATE booking_order SET status = 'CANCELLED' WHERE id = ? AND status = 'PENDING'", id) == 0) {
            order(id);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有待支付订单可以取消");
        }
        Order order = order(id);
        jdbc.update("UPDATE activity SET remaining_stock = remaining_stock + 1 WHERE id = ?", order.activityId());
        activityCache.evictAfterCommit(order.activityId());
        return order;
    }
}
