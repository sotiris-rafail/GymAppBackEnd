package com.foundry.gym.web;

import com.foundry.gym.dto.Dtos.ApiError;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getDefaultMessage())
            .findFirst().orElse("The request is not valid.");
        return bad(msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraint(ConstraintViolationException e) {
        return bad(e.getConstraintViolations().stream().map(v -> v.getMessage()).findFirst().orElse("The request is not valid."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return bad("The request body is missing or has a value in the wrong format.");
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> parameter(Exception e) {
        return bad("A query parameter is missing or in the wrong format. Dates look like 2026-10-08.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation", e);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("That change conflicts with existing data. Reload and try again."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception e) throws Exception {
        if (e instanceof org.springframework.security.core.AuthenticationException
            || e instanceof org.springframework.security.access.AccessDeniedException) {
            throw e; // let Spring Security answer with 401 or 403
        }
        if (e instanceof ErrorResponse er) {
            // Standard Spring MVC errors (404 unknown path, 405 wrong method, 415 wrong content type) keep their status.
            String detail = er.getBody().getDetail();
            return ResponseEntity.status(er.getStatusCode()).body(new ApiError(detail != null ? detail : "The request could not be handled."));
        }
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("Something went wrong on the server."));
    }

    private static ResponseEntity<ApiError> bad(String message) {
        return ResponseEntity.badRequest().body(new ApiError(message));
    }
}
