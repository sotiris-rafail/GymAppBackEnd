package com.foundry.gym.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import com.foundry.gym.domain.Role;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response shapes for the JSON API. */
public final class Dtos {

    private Dtos() {
    }

    // ----- requests -----

    public record LoginRequest(
        @NotBlank(message = "Email is required.") String email,
        @NotBlank(message = "Password is required.") String password,
        @NotNull(message = "Login type is required.") Role role) {
    }

    /** The only thing a signed-in person may change about their own account. */
    public record PasswordChange(
        @NotBlank(message = "Enter your current password.") String currentPassword,
        @NotBlank(message = "Enter a new password.")
        @Size(min = 6, max = 72, message = "Password must be 6 to 72 characters.") String newPassword) {
    }

    public record RegisterRequest(
        @NotBlank(message = "Name is required.") @Size(max = 60, message = "Name can be at most 60 characters.") String name,
        @NotBlank(message = "Email is required.") @Email(message = "Enter a valid email address.")
        @Size(max = 120, message = "Email can be at most 120 characters.") String email,
        @NotBlank(message = "Password is required.")
        @Size(min = 6, max = 72, message = "Password must be 6 to 72 characters.") String password) {
    }

    public record PlanRequest(
        @NotBlank(message = "Plan name is required.") @Size(max = 40, message = "Plan name can be at most 40 characters.") String name,
        @NotNull(message = "Price is required.") @DecimalMin(value = "0.00", message = "Price cannot be negative.")
        @Digits(integer = 6, fraction = 2, message = "Price can have at most 2 decimals.") BigDecimal price,
        @NotNull(message = "Visits per month is required.") @Min(value = 0, message = "Visits per month cannot be negative.")
        @Max(value = 99, message = "Visits per month can be at most 99.") Integer visitsPerMonth) {
    }

    /** Photo: empty, an https address, or a small image as a data URL (the UI shrinks uploads before sending). */
    public record PromoRequest(
        @NotNull(message = "Type is required.") @Pattern(regexp = "PHOTO|INSTAGRAM|PROMO|CARD", message = "Type must be PHOTO, INSTAGRAM, PROMO or CARD.") String kind,
        @NotBlank(message = "Title is required.") @Size(max = 80, message = "Title can be at most 80 characters.") String title,
        @Size(max = 500, message = "Text can be at most 500 characters.") String body,
        @Size(max = 2000000, message = "The photo is too large.")
        @Pattern(regexp = "^(https?://\\S+|data:image/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/=]+)?$", message = "The photo must be an http(s) address or an uploaded image.") String image,
        @Size(max = 500, message = "Link can be at most 500 characters.")
        @Pattern(regexp = "^(https?://\\S+)?$", message = "The link must start with http:// or https://.") String linkUrl,
        @Min(value = 0, message = "Order cannot be negative.") Integer sortOrder,
        Boolean active,
        Boolean bordered,
        Boolean titleVisible,
        @Size(max = 20000, message = "The card text can be at most 20000 characters.") String content,
        LocalDate hideOn,
        @Min(value = 0, message = "Width cannot be negative.") @Max(value = 4, message = "Width can be at most 4 columns.") Integer colSpan,
        @Min(value = 0, message = "Height cannot be negative.") @Max(value = 8, message = "Height can be at most 8 rows.") Integer rowSpan,
        @Pattern(regexp = "^(#[0-9a-fA-F]{6})?$", message = "The background colour must look like #1a2b3c.") String bgColor,
        @Size(max = 2000000, message = "The background picture is too large.")
        @Pattern(regexp = "^(https?://\\S+|data:image/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/=]+)?$", message = "The background picture must be an http(s) address or an uploaded image.") String bgImage) {
    }

    public record ClassRequest(
        @NotBlank(message = "Class name is required.") @Size(max = 40, message = "Class name can be at most 40 characters.") String name,
        @Size(max = 40, message = "Coach name can be at most 40 characters.") String coach,
        @NotNull(message = "Start time is required.") LocalTime startTime,
        @NotNull(message = "Length is required.") @Min(value = 5, message = "Length must be at least 5 minutes.")
        @Max(value = 240, message = "Length can be at most 240 minutes.") Integer durationMinutes,
        @NotNull(message = "Max people is required.") @Min(value = 1, message = "Max people must be at least 1.")
        @Max(value = 200, message = "Max people can be at most 200.") Integer capacity,
        @NotNull(message = "Days are required.") Set<@Min(value = 1, message = "Days are numbered 1 (Mon) to 7 (Sun).")
            @Max(value = 7, message = "Days are numbered 1 (Mon) to 7 (Sun).") Integer> days) {
    }

    /** planId null means "no plan". */
    public record PlanAssign(Long planId) {
    }

    public record BookingRequest(
        @NotNull(message = "Class is required.") Long classId,
        @NotNull(message = "Date is required.") LocalDate date) {
    }

    // ----- responses -----

    public record UserView(Long id, String name, String email, Role role, Long planId) {
    }

    public record PlanView(Long id, String name, BigDecimal price, int visitsPerMonth) {
    }

    public record PromoView(Long id, String kind, String title, String body, String image, String linkUrl,
                            int sortOrder, boolean active, boolean bordered,
                            boolean titleVisible, String content,
                            String hideOn, int colSpan, int rowSpan, String bgColor, String bgImage) {
    }

    /** Days use ISO numbers: 1 = Monday ... 7 = Sunday. */
    public record ClassView(Long id, String name, String coach, String startTime, int durationMinutes,
                            int capacity, java.util.List<Integer> days) {
    }

    /** One class on one date, with how full it is and whether the signed-in member holds a seat. */
    /** Which emails a member wants. Used both to read and to save the choices. */
    public record PreferencesBody(
        @NotNull(message = "Marketing is required.") Boolean marketing,
        @NotNull(message = "New plans is required.") Boolean newPlans,
        @NotNull(message = "Plan ending is required.") Boolean planEnding,
        @NotNull(message = "Visits reminder is required.") Boolean visitsReminder) {
    }

    public record ClosedDayRequest(
        @NotNull(message = "Date is required.") LocalDate date,
        @Size(max = 80, message = "Reason can be at most 80 characters.") String reason) {
    }

    public record ClosedDayView(String date, String reason) {
    }

    public record SessionView(Long classId, String date, String name, String coach, String startTime,
                              int durationMinutes, int capacity, int booked, boolean mine, Long bookingId) {
    }

    public record BookingView(Long id, Long classId, String className, String coach, String date,
                              String startTime, int durationMinutes) {
    }

    public record MemberView(Long id, String name, String email, Long planId, String planName,
                             Integer visitsPerMonth, int visitsUsedThisMonth) {
    }

    /** One period on one plan. "to" is null while the plan is still active. */
    public record PlanPeriod(String planName, java.math.BigDecimal price, int visitsPerMonth,
                             java.time.Instant from, java.time.Instant to) {
    }

    /** Visits used in one month ("2026-10"), and the plan the member had at the end of that month, if any. */
    public record MonthUsage(String month, long visits, String planName, Integer visitsPerMonth) {
    }

    public record MemberHistory(Long id, String name, String email, List<PlanPeriod> plans, List<MonthUsage> months) {
    }

    public record Attendee(Long id, String name) {
    }

    /** One class on one date and the members booked into it. */
    public record SessionAttendance(LocalDate date, List<Attendee> attendees) {
    }

    /** A class with every session in the month that has at least one booking. */
    public record ClassAttendance(Long classId, String name, String coach, LocalTime startTime, int capacity,
                                  int totalBookings, List<SessionAttendance> sessions) {
    }

    public record ApiError(String message) {
    }
}
