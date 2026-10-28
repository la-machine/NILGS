// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.conflict.infrastructure.web;

import java.util.List;

import org.snigf.mvp.conflict.application.ConflictRefused;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Errors of the alert endpoints as RFC 9457 problems with stable codes (ADR-011):
 * {@code ALERT_NOT_FOUND}, {@code ALERT_NOT_OPEN}, {@code STALE_VERSION},
 * {@code RESOLVER_IS_AUTHOR}, {@code RESOLUTION_REQUIRED}, {@code NOTE_REQUIRED},
 * {@code NOTE_TOO_LONG}. Nothing else from the server is echoed.
 */
@RestControllerAdvice(assignableTypes = {AlertController.class, ConflictController.class})
class ConflictExceptionHandler {

    @ExceptionHandler(ConflictRefused.class)
    ResponseEntity<ProblemDetail> refused(ConflictRefused e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
        return ResponseEntity.status(status).body(body(status, e.code()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        ProblemDetail problem = body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
        List<String> fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).distinct().toList();
        problem.setProperty("fields", fields);
        return ResponseEntity.unprocessableContent().body(problem);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> unreadable() {
        return ResponseEntity.unprocessableContent().body(body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED"));
    }

    private static ProblemDetail body(HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }
}
