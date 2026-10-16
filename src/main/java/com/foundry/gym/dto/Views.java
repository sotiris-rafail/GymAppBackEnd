package com.foundry.gym.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.foundry.gym.domain.AppUser;
import com.foundry.gym.domain.Booking;
import com.foundry.gym.domain.GymClass;
import com.foundry.gym.domain.Plan;
import com.foundry.gym.dto.Dtos.BookingView;
import com.foundry.gym.dto.Dtos.ClassView;
import com.foundry.gym.dto.Dtos.PlanView;
import com.foundry.gym.dto.Dtos.UserView;

/** Entity to response mapping, kept in one place so times and days always look the same. */
public final class Views {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private Views() {
    }

    public static String time(LocalTime t) {
        return t.format(HH_MM);
    }

    public static UserView user(AppUser u) {
        Long planId = u.getPlan() == null ? null : u.getPlan().getId();
        return new UserView(u.getId(), u.getName(), u.getEmail(), u.getRole(), planId);
    }

    public static PlanView plan(Plan p) {
        return new PlanView(p.getId(), p.getName(), p.getPrice(), p.getVisitsPerMonth(), p.isUnlimited());
    }

    public static ClassView clazz(GymClass c) {
        List<Integer> days = c.getDays().stream().map(DayOfWeek::getValue).sorted().toList();
        return new ClassView(c.getId(), c.getName(), c.getCoach() == null ? "" : c.getCoach(),
            time(c.getStartTime()), c.getDurationMinutes(), c.getCapacity(), days);
    }

    public static BookingView booking(Booking b) {
        GymClass c = b.getGymClass();
        return new BookingView(b.getId(), c.getId(), c.getName(), c.getCoach() == null ? "" : c.getCoach(),
            b.getSessionDate().toString(), time(c.getStartTime()), c.getDurationMinutes());
    }
}
