// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.procedure.infrastructure.web;

import org.snigf.mvp.procedure.application.ProcedureRefused;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Procedure refusals as problem details with a precise {@code code} (ADR-011). */
@RestControllerAdvice(assignableTypes = {ProcedureController.class, PublicNoticeController.class, PartyController.class})
class ProcedureExceptionHandler {

    @ExceptionHandler(ProcedureRefused.class)
    ResponseEntity<ProblemDetail> refused(ProcedureRefused e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return ResponseEntity.status(status).body(body(status, e.code()));
    }

    @ExceptionHandler(NoticeRateLimiter.Limited.class)
    ResponseEntity<ProblemDetail> limited(NoticeRateLimiter.Limited e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(body(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<ProblemDetail> unreadable() {
        return ResponseEntity.unprocessableContent().body(body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED"));
    }

    private static ProblemDetail body(HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }
}
