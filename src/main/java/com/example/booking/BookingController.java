package com.example.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class BookingController {
    private final BookingService service;
    public BookingController(BookingService service) { this.service = service; }

    public record CreateActivity(@NotBlank @Size(max = 100) String title,
            @NotNull @Positive Integer stock,
            @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price) {}
    public record CreateOrder(@Positive long activityId, @Positive long userId) {}

    @PostMapping("/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingService.Activity createActivity(@Valid @RequestBody CreateActivity request) {
        return service.createActivity(request.title(), request.stock(), request.price());
    }

    @GetMapping("/activities")
    public List<BookingService.Activity> activities() { return service.activities(); }

    @GetMapping("/activities/{id}")
    public BookingService.Activity activity(@PathVariable long id) { return service.activity(id); }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingService.Order book(@Valid @RequestBody CreateOrder request) {
        return service.book(request.activityId(), request.userId());
    }

    @GetMapping("/orders/{id}")
    public BookingService.Order order(@PathVariable long id) { return service.order(id); }

    @GetMapping("/orders")
    public List<BookingService.Order> orders(@RequestParam @Positive long userId) { return service.ordersForUser(userId); }

    @PostMapping("/orders/{id}/pay")
    public BookingService.Order pay(@PathVariable long id) { return service.pay(id); }

    @PostMapping("/orders/{id}/cancel")
    public BookingService.Order cancel(@PathVariable long id) { return service.cancel(id); }
}
