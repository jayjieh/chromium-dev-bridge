package dev.cdb.driver.web;

import dev.cdb.core.BridgeException;
import dev.cdb.protocol.ErrorCode;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to RFC 9457 Problem Details with a machine-readable "code". */
@RestControllerAdvice
public class ProblemHandler {
    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(BridgeException.class)
    ResponseEntity<ProblemDetail> bridge(BridgeException e) {
        if (e.code() == ErrorCode.INTERNAL || e.code() == ErrorCode.BROWSER_ERROR) {
            log.warn("{}: {}", e.code(), e.getMessage());
        }
        return problem(e.code(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> badRequest(Exception e) {
        String msg = e instanceof HttpMessageNotReadableException ? "Malformed JSON request body" : e.getMessage();
        return problem(ErrorCode.VALIDATION_FAILED, msg);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NoResourceFoundException e) {
        return problem(ErrorCode.NOT_FOUND, "No such endpoint: " + e.getResourcePath());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(HttpRequestMethodNotSupportedException e) {
        ProblemDetail pd = base(HttpStatus.METHOD_NOT_ALLOWED.value(), "METHOD_NOT_ALLOWED", e.getMessage());
        pd.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(pd);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException e) {
        return problem(ErrorCode.VALIDATION_FAILED, "Use Content-Type: application/json");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> other(Exception e) {
        log.error("Unexpected error", e);
        return problem(ErrorCode.INTERNAL, "Internal error (correlation id " + MDC.get("correlationId") + ")");
    }

    static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail) {
        ProblemDetail pd = base(code.httpStatus(), code.name(), detail);
        pd.setProperty("code", code.name());
        return ResponseEntity.status(code.httpStatus()).body(pd);
    }

    private static ProblemDetail base(int status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(URI.create("https://cdb.dev/problems/" + title.toLowerCase(Locale.ROOT).replace('_', '-')));
        pd.setTitle(title);
        pd.setDetail(detail);
        String corr = MDC.get("correlationId");
        if (corr != null) pd.setProperty("correlationId", corr);
        return pd;
    }
}
