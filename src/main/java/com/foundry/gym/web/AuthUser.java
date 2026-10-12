package com.foundry.gym.web;

import java.io.Serializable;

import com.foundry.gym.domain.Role;

/** What the session remembers about the signed-in person. */
public record AuthUser(Long id, Role role) implements Serializable {
}
