package com.supportforge.api.error;

import com.supportforge.knowledge.KnowledgeConflictException;
import com.supportforge.knowledge.KnowledgeNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import org.springframework.web.ErrorResponse;
import java.util.stream.Collectors;

import com.supportforge.feedback.FeedbackTargetNotFoundException;


@RestControllerAdvice
public class GlobalExceptionHandler {


    private static final Logger log =
            LoggerFactory.getLogger(
                    GlobalExceptionHandler.class
            );


    @ExceptionHandler(KnowledgeNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            KnowledgeNotFoundException exception,
            HttpServletRequest request
    ) {

        return response(
                HttpStatus.NOT_FOUND,
                exception.getMessage(),
                request
        );
    }


    @ExceptionHandler(
            FeedbackTargetNotFoundException.class
    )
    public ResponseEntity<ApiError> handleFeedbackNotFound(
            FeedbackTargetNotFoundException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                exception.getMessage(),
                request
        );
    }


    @ExceptionHandler(KnowledgeConflictException.class)
    public ResponseEntity<ApiError> handleConflict(
            KnowledgeConflictException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                request
        );
    }


    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {

        String message =
                exception.getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .map(fieldError ->
                                fieldError.getField()
                                        + ": "
                                        + fieldError.getDefaultMessage()
                        )
                        .distinct()
                        .collect(Collectors.joining("; "));

        return response(
                HttpStatus.BAD_REQUEST,
                message,
                request
        );
    }


    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                request
        );
    }


    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> handleInvalidRequest(
            Exception exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "请求参数缺失、格式错误或字段类型不正确",
                request
        );
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {


        if (exception instanceof ErrorResponse error) {
            var status = error.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            String reason = known == null ? "HTTP " + status.value() : known.getReasonPhrase();
            String message = status.is5xxServerError() ? reason
                    : java.util.Objects.requireNonNullElse(error.getBody().getDetail(), reason);
            return ResponseEntity.status(status).headers(error.getHeaders()).body(new ApiError(
                    Instant.now(), status.value(), reason, message, request.getRequestURI()));
        }

        log.error(
                "处理请求时发生未预期异常，path={}",
                request.getRequestURI(),
                exception
        );

        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "服务器内部错误",
                request
        );
    }


    private ResponseEntity<ApiError> response(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {

        ApiError error = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        );


        return ResponseEntity
                .status(status)
                .body(error);
    }
}
