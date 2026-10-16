package com.foundry.gym.web;

import com.foundry.gym.dto.Dtos.BookingWindowBody;
import com.foundry.gym.service.BookingWindowService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The booking window: readable by any signed-in user, changed by the admin. */
@RestController
@RequestMapping("/api")
public class BookingWindowController {

    private final BookingWindowService window;

    public BookingWindowController(BookingWindowService window) {
        this.window = window;
    }

    @GetMapping("/booking-window")
    public BookingWindowBody get() {
        return new BookingWindowBody(window.mode());
    }

    @PutMapping("/admin/booking-window")
    public BookingWindowBody set(@Valid @RequestBody BookingWindowBody body) {
        return new BookingWindowBody(window.setMode(body.mode()));
    }
}
