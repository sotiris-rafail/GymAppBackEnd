package com.foundry.gym.mail;

/** The four emails a member can switch on in Preferences, plus the notice that a booking was cancelled because the gym closed (sent whatever the choices). */
public enum EmailKind {
    MARKETING, NEW_PLAN, PLAN_ENDING, VISITS_REMINDER, CLASS_CANCELLED
}
