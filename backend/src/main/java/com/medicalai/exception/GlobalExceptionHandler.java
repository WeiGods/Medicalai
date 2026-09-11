package com.medicalai.exception;

import com.medicalai.vo.ErrorVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.transaction.CannotCreateTransactionException;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorVO> business(BusinessException e, HttpServletRequest request) {
        // Business errors are normally safe to show to the caller, but still
        // need a server-side trace.  In particular this preserves the real
        // ASR client exception that would otherwise be hidden behind the
        // generic “转写服务暂不可用” message.
        LOG.warn("API business error: method={}, uri={}, status={}, code={}, message={}",
                request.getMethod(), request.getRequestURI(), e.status().value(), e.code(), e.getMessage(), e);
        return ResponseEntity.status(e.status()).body(new ErrorVO(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorVO> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(x -> x.getDefaultMessage()).orElse("请求参数无效");
        return ResponseEntity.badRequest().body(new ErrorVO("VALIDATION_ERROR", message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorVO> malformed(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorVO("INVALID_REQUEST", "请求格式或编号无效"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorVO> conflict(DataIntegrityViolationException e, HttpServletRequest request) {
        // Keep the stable API response while retaining the concrete constraint
        // and endpoint in the server log.  Without this, an export constraint
        // failure is indistinguishable from a workflow-state conflict.
        LOG.warn("API data conflict: method={}, uri={}, message={}",
                request.getMethod(), request.getRequestURI(), e.getMostSpecificCause().getMessage(), e);
        return ResponseEntity.status(409).body(new ErrorVO("DATA_CONFLICT", "数据状态冲突，请刷新后重试"));
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ErrorVO> database(Exception e) {
        LOG.error("Database operation failed: {}", e.getClass().getSimpleName(), e);
        return ResponseEntity.status(503).body(new ErrorVO("DATABASE_UNAVAILABLE", "数据库暂不可用"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorVO> unexpected(Exception e, HttpServletRequest request) {
        LOG.error("Unhandled API error: method={}, uri={}, exceptionType={}, message={}",
                request.getMethod(), request.getRequestURI(), e.getClass().getName(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorVO("INTERNAL_ERROR", "服务器处理失败，请查看后端日志"));
    }
}
