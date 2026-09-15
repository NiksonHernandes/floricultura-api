package com.floricultura.api.web.error;

import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
import com.floricultura.api.web.response.FieldErrorItem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Tratamento centralizado de erros no envelope padrao (SPEC-M0 §3.1/§3.2/§4/§9).
 *
 * <p>Nenhuma resposta vaza stacktrace, SQL ou segredo: o detalhe tecnico so vai para o log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Corpo invalido em @Valid de @RequestBody -> 400 com a lista de campos. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleBodyValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldErrorItem> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorItem(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                details, request);
    }

    /** Validacao de parametros de metodo (@Valid em @RequestParam/@PathVariable) -> 400. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        List<FieldErrorItem> details = ex.getAllErrors().stream()
                .map(err -> new FieldErrorItem("", err.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                details, request);
    }

    /** Violacao de constraint (@Validated em service/param) -> 400. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldErrorItem> details = ex.getConstraintViolations().stream()
                .map(v -> new FieldErrorItem(String.valueOf(v.getPropertyPath()), v.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                details, request);
    }

    /**
     * Rota inexistente sob {@code /api/v1} -> 404 no envelope. No Boot 4, com
     * {@code add-mappings=false} + {@code throw-exception-if-no-handler-found=true}, uma URL
     * desconhecida pode lancar {@link NoHandlerFoundException} OU
     * {@link NoResourceFoundException} — tratamos as duas (SPEC-M0 §12).
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Object>> handleNotFound(
            Exception ex, HttpServletRequest request) {
        return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage(), List.of(), request);
    }

    /** Violacao de unicidade/estado (ex.: e-mail duplicado) -> 409. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConflict(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Conflito de integridade em {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.CONFLICT, ErrorCode.CONFLICT.defaultMessage(), List.of(), request);
    }

    /**
     * Path/query com tipo incompativel (ex.: {@code /produtos/abc}, {@code ?precoMin=abc}) -> 400,
     * nunca 500 (M6.1/D3). Sem este handler o erro de CONVERSAO do Spring cai no catch-all abaixo e
     * vira {@code INTERNAL_ERROR}, culpando o servidor por um erro do cliente. O corpo leva apenas o
     * NOME do parametro — a mensagem do Spring ("Failed to convert value of type 'java.lang.String'
     * to required type 'java.lang.Long'") vazaria detalhe tecnico (SPEC-M0 §9) e o valor recebido
     * nao e ecoado. Log em {@code debug}: e erro do cliente, nao incidente de servidor.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTipoInvalido(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.debug("Parametro de tipo invalido em {}: {}", request.getRequestURI(), ex.getName());
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                List.of(new FieldErrorItem(ex.getName(), "Valor invalido.")), request);
    }

    /** Qualquer excecao nao mapeada -> 500 generico; detalhe apenas no log. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Erro nao tratado em {}", request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(),
                List.of(), request);
    }

    private ResponseEntity<ApiResponse<Object>> build(
            ErrorCode code, String message, List<FieldErrorItem> details, HttpServletRequest request) {
        ApiError error = new ApiError(code.name(), message, details);
        return ResponseEntity.status(code.status())
                .body(ApiResponse.fail(error, request.getRequestURI()));
    }
}
