package com.foundry.gym.web;

import java.util.List;

import com.foundry.gym.dto.Dtos.LoginRequest;
import com.foundry.gym.dto.Dtos.RegisterRequest;
import com.foundry.gym.dto.Dtos.UserView;
import com.foundry.gym.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AccountService accounts;
    private final SecurityContextRepository contextRepository;

    public AuthController(AccountService accounts, SecurityContextRepository contextRepository) {
        this.accounts = accounts;
        this.contextRepository = contextRepository;
    }

    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        UserView user = accounts.login(body.email(), body.password(), body.role());
        signIn(user, req, res);
        return user;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterRequest body, HttpServletRequest req, HttpServletResponse res) {
        UserView user = accounts.registerMember(body.name(), body.email(), body.password());
        signIn(user, req, res);
        return user;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthUser principal) {
        return accounts.me(principal.id());
    }

    private void signIn(UserView user, HttpServletRequest req, HttpServletResponse res) {
        if (req.getSession(false) != null) {
            req.changeSessionId();
        }
        AuthUser principal = new AuthUser(user.id(), user.role());
        var token = UsernamePasswordAuthenticationToken.authenticated(principal, null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, req, res);
    }
}
