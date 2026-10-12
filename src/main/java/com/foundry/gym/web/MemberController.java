package com.foundry.gym.web;

import java.time.LocalDate;
import java.util.List;

import com.foundry.gym.dto.Dtos.BookingRequest;
import com.foundry.gym.dto.Dtos.BookingView;
import com.foundry.gym.dto.Dtos.SessionView;
import com.foundry.gym.service.AccountService;
import com.foundry.gym.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Member-only endpoints (enforced in SecurityConfig). Each one acts on the signed-in member. Members cannot change their own plan; the admin assigns it. */
@RestController
@RequestMapping("/api")
public class MemberController {

    private final AccountService accounts;
    private final BookingService bookings;

    public MemberController(AccountService accounts, BookingService bookings) {
        this.accounts = accounts;
        this.bookings = bookings;
    }

    @GetMapping("/me/bookings")
    public List<BookingView> myBookings(@AuthenticationPrincipal AuthUser me) {
        return bookings.myBookings(me.id());
    }

    @GetMapping("/sessions")
    public List<SessionView> sessions(@AuthenticationPrincipal AuthUser me,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return bookings.sessions(me.id(), from, to);
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView book(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody BookingRequest body) {
        return bookings.book(me.id(), body.classId(), body.date());
    }

    @DeleteMapping("/bookings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        bookings.cancel(me.id(), id);
    }
}
