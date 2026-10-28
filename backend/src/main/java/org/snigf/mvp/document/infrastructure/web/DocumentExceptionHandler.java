// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.infrastructure.web;

import org.snigf.mvp.document.application.DocumentRefused;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Errors of the document endpoints as RFC 9457 problems with stable codes (ADR-011):
 * {@code FILE_TOO_LARGE} (413), {@code TYPE_NOT_ALLOWED}, {@code TYPE_MISMATCH},
 * {@code PDF_ACTIVE_CONTENT} ... (422), {@code DOCUMENTS_CLOSED} (409),
 * {@code DOCUMENT_INTEGRITY_FAILED} (500, the bytes are never served).
 */
@RestControllerAdvice(assignableTypes = {DocumentController.class, CaseDocumentController.class,
        AssignedCaseDocumentController.class})
class DocumentExceptionHandler {

    @ExceptionHandler(DocumentRefused.class)
    ResponseEntity<ProblemDetail> refused(DocumentRefused e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
            case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case BROKEN -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(body(status, e.code()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(body(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE"));
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> unreadable() {
        return ResponseEntity.unprocessableContent().body(body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED"));
    }

    private static ProblemDetail body(HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }
}
