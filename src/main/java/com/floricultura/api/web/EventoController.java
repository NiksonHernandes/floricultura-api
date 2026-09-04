package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.service.DataEventoInvalidaException;
import com.floricultura.api.service.EventoAlertaService;
import com.floricultura.api.service.EventoNaoEncontradoException;
import com.floricultura.api.service.EventoService;
import com.floricultura.api.web.dto.EventoProximoResponse;
import com.floricultura.api.web.dto.EventoRequest;
import com.floricultura.api.web.dto.EventoResponse;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.ParametroPaginacaoInvalidoException;
import com.floricultura.api.web.error.ErrorCode;
import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD de eventos do M4 (T-M4-2, CA-1..CA-8 — SPEC-M4 §3.2/§3.6). Toda resposta trafega no envelope
 * {@link ApiResponse} do M0. Reusa o padrao do {@code ProdutoController} (envelope, {@code
 * PaginacaoParams}, handlers locais, paginacao AD-SQ-29).
 *
 * <p><b>RBAC (FC-07):</b> {@code GET /eventos/**} e autenticado (USER+ADMIN) via
 * {@code anyRequest().authenticated()}; {@code POST}/{@code PUT}/{@code DELETE} de {@code /eventos/**}
 * exigem {@code ROLE_ADMIN} (matchers §3.6 no {@code SecurityConfig}) — USER → {@code 403}, sem token →
 * {@code 401}.
 *
 * <ul>
 *   <li>{@code POST /api/v1/eventos} — 201 com {@link EventoResponse}; 400 validacao (enum/datas); 403
 *       para USER.</li>
 *   <li>{@code GET /api/v1/eventos} — 200 com {@link PaginaResponse} (params {@code pagina}/{@code
 *       tamanho}/{@code nome}), ordem {@code dataInicio ASC, nome ASC}; range invalido → 400.</li>
 *   <li>{@code GET /api/v1/eventos/{id}} — 200 detalhe; 404 se inexistente.</li>
 *   <li>{@code PUT /api/v1/eventos/{id}} — 200 atualizado; 400/403; 404 se inexistente.</li>
 *   <li>{@code DELETE /api/v1/eventos/{id}} — 204 (hard delete, cascade limpa {@code evento_produto});
 *       403 para USER; 404 se inexistente.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/eventos")
@Tag(name = "eventos", description = "CRUD de eventos (sazonalidade): leitura (USER+ADMIN) paginada/"
        + "detalhe e escrita (ADMIN) criar/atualizar/deletar")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class EventoController {

    private final EventoService eventoService;
    private final EventoAlertaService eventoAlertaService;

    public EventoController(
            EventoService eventoService, EventoAlertaService eventoAlertaService) {
        this.eventoService = eventoService;
        this.eventoAlertaService = eventoAlertaService;
    }

    /**
     * CA-13..CA-17: lista os "proximos eventos" (janela {@code diasAte <= 60}), calculados on-read com
     * {@code Clock} em {@code America/Sao_Paulo} (§4.3/§4.4), ja filtrados e ordenados por
     * {@code proximaOcorrencia ASC, nome ASC}. USER+ADMIN (catch-all autenticado, sem matcher novo).
     * Rota literal {@code /proximos} — precede {@code /{id}} na resolucao do Spring.
     */
    @Operation(summary = "Lista proximos eventos (janela 60d, on-read), ordem proximaOcorrencia ASC")
    @GetMapping("/proximos")
    public ApiResponse<List<EventoProximoResponse>> proximos(HttpServletRequest http) {
        return ApiResponse.ok(eventoAlertaService.proximos(), http.getRequestURI());
    }

    /**
     * CA-4: lista eventos paginados (default {@code pagina=0}, {@code tamanho=20}), ordenados por
     * {@code dataInicio ASC, nome ASC}, com filtro opcional {@code nome} ({@code ILIKE '%nome%'}).
     * {@code tamanho} fora de {@code 1..100} ou {@code pagina < 0} → 400 VALIDATION_ERROR.
     */
    @Operation(summary = "Lista eventos paginados (pagina/tamanho/nome), ordem dataInicio ASC, nome ASC")
    @GetMapping
    public ApiResponse<PaginaResponse<EventoResponse>> listar(
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho,
            @RequestParam(required = false) String nome,
            HttpServletRequest http) {
        return ApiResponse.ok(eventoService.listar(pagina, tamanho, nome), http.getRequestURI());
    }

    /** CA-5: detalha evento por id; inexistente → 404. */
    @Operation(summary = "Detalha evento por id (404 se inexistente)")
    @GetMapping("/{id}")
    public ApiResponse<EventoResponse> detalhar(@PathVariable Long id, HttpServletRequest http) {
        return ApiResponse.ok(eventoService.detalhar(id), http.getRequestURI());
    }

    /**
     * CA-1/CA-2/CA-3: cria evento (ADMIN) → 201 com {@link EventoResponse} ({@code dataUnica} computado).
     * Payload invalido (enum fora do conjunto, {@code nome} vazio) → 400; {@code dataFim < dataInicio} →
     * 400 {@code field=dataFim} (handler local). USER → 403 (SecurityConfig).
     */
    @Operation(summary = "Cria evento (ADMIN) → 201; dataFim<dataInicio → 400 field=dataFim")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EventoResponse> criar(
            @Valid @RequestBody EventoRequest request, HttpServletRequest http) {
        return ApiResponse.ok(eventoService.criar(request), http.getRequestURI());
    }

    /**
     * CA-6: atualiza evento (ADMIN) → 200 com os campos atualizados. Payload invalido → 400;
     * {@code dataFim < dataInicio} → 400; inexistente → 404 (handler local); USER → 403 (SecurityConfig).
     */
    @Operation(summary = "Atualiza evento (ADMIN) → 200 (404 se inexistente)")
    @PutMapping("/{id}")
    public ApiResponse<EventoResponse> atualizar(
            @PathVariable Long id,
            @Valid @RequestBody EventoRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(eventoService.atualizar(id, request), http.getRequestURI());
    }

    /**
     * CA-7: hard delete de evento (ADMIN) → 204 sem corpo (FC-08). O {@code ON DELETE CASCADE} da V6
     * limpa os vinculos em {@code evento_produto}; os produtos permanecem. Inexistente → 404 (handler
     * local); USER → 403 (SecurityConfig). A confirmacao e do front; o back so executa.
     */
    @Operation(summary = "Hard delete de evento (ADMIN) → 204 (404 se inexistente)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletar(@PathVariable Long id) {
        eventoService.deletar(id);
    }

    // ---- Handlers locais (nao tocam o GlobalExceptionHandler do M0 — §8) ----------------------

    /** 400 VALIDATION_ERROR para {@code pagina}/{@code tamanho} fora do contrato §3.3 (CA-4). */
    @ExceptionHandler(ParametroPaginacaoInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handlePaginacaoInvalida(
            ParametroPaginacaoInvalidoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(
                ErrorCode.VALIDATION_ERROR.name(), ex.getMessage(), ex.getDetails());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }

    /** 400 VALIDATION_ERROR para a regra cruzada {@code dataFim < dataInicio} (CA-2). */
    @ExceptionHandler(DataEventoInvalidaException.class)
    public ResponseEntity<ApiResponse<Object>> handleDataInvalida(
            DataEventoInvalidaException ex, HttpServletRequest http) {
        ApiError error = new ApiError(
                ErrorCode.VALIDATION_ERROR.name(), ex.getMessage(), ex.getDetails());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }

    /** 404 NOT_FOUND para id inexistente em GET/PUT/DELETE {@code /{id}} (CA-5/CA-6/CA-7). */
    @ExceptionHandler(EventoNaoEncontradoException.class)
    public ResponseEntity<ApiResponse<Object>> handleNaoEncontrado(
            EventoNaoEncontradoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.NOT_FOUND.name(), ex.getMessage(), List.of());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
