// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.professional.infrastructure.web;

import org.snigf.mvp.professional.application.ProfessionalRefused;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Professional-account refusals as problem details with a precise {@code code} (ADR-011). */
@RestControllerAdvice(assignableTypes = ProfessionalController.class)
class ProfessionalExceptionHandler {

    @ExceptionHandler(ProfessionalRefused.class)
    ResponseEntity<ProblemDetail> refused(ProfessionalRefused e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
            case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case BROKEN -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        ProblemDetail problem = body(status, e.code());
        if (!e.violations().isEmpty()) {
            problem.setProperty("violations", e.violations());
        }
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(ProfessionalRateLimiter.Limited.class)
    ResponseEntity<ProblemDetail> limited(ProfessionalRateLimiter.Limited e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(body(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(body(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MultipartException.class, MissingServletRequestPartException.class})
    ResponseEntity<ProblemDetail> unreadable() {
        return ResponseEntity.unprocessableContent().body(body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED"));
    }

    private static ProblemDetail body(HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }
}
