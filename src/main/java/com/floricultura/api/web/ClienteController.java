package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.service.ClienteNaoEncontradoException;
import com.floricultura.api.service.ClienteService;
import com.floricultura.api.web.dto.ClienteRequest;
import com.floricultura.api.web.dto.ClienteResponse;
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
 * Leitura de clientes do M5 (T-M5-2, CA-1/CA-2/CA-3 — SPEC-M5 §3.4). Toda resposta trafega no envelope
 * {@link ApiResponse} do M0. Reusa o padrao do {@code EventoController} (envelope, {@code
 * PaginacaoParams}, handlers locais, paginacao AD-SQ-29). A escrita (POST/PUT/DELETE) e da T-M5-4.
 *
 * <p><b>RBAC (FC-07):</b> {@code GET /clientes/**} e autenticado (USER+ADMIN) via
 * {@code anyRequest().authenticated()} — <b>sem</b> matcher de GET no {@code SecurityConfig}.
 *
 * <ul>
 *   <li>{@code GET /api/v1/clientes} — 200 {@link PaginaResponse} (params {@code pagina}/{@code
 *       tamanho}/{@code nome}), ordem {@code nome ASC}, itens com {@code produtoIds=null}; range
 *       invalido → 400.</li>
 *   <li>{@code GET /api/v1/clientes/{id}} — 200 detalhe; 404 se inexistente.</li>
 *   <li>{@code POST /api/v1/clientes} — 201 {@link ClienteResponse} (ADMIN); 400 validacao; 403 USER;
 *       401 s/ token.</li>
 *   <li>{@code PUT /api/v1/clientes/{id}} — 200 atualizado; 400/403; 404 inexistente.</li>
 *   <li>{@code DELETE /api/v1/clientes/{id}} — 204 (hard delete); 403 USER; 404 inexistente.</li>
 * </ul>
 *
 * <p><b>Revisao 2026-09-04 (AD-SQ-65):</b> o vinculo cliente↔produto deixou de ser junção N:N editavel
 * pelo cadastro; nao ha mais {@code produtoIds} de escrita nem replace-set.
 */
@RestController
@RequestMapping("/api/v1/clientes")
@Tag(name = "clientes", description = "Cadastro de clientes (LGPD): leitura (USER+ADMIN) paginada/detalhe "
        + "e escrita (ADMIN) criar/atualizar/excluir")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ClienteController {

    private final ClienteService clienteService;

    public ClienteController(ClienteService clienteService) {
        this.clienteService = clienteService;
    }

    /**
     * CA-1: lista clientes paginados (default {@code pagina=0}, {@code tamanho=20}), ordenados por
     * {@code nome ASC}, com filtro opcional {@code nome} ({@code ILIKE '%nome%'}). {@code tamanho} fora
     * de {@code 1..100} ou {@code pagina < 0} → 400 VALIDATION_ERROR. Itens com {@code produtoIds=null}
     * (CA-3).
     */
    @Operation(summary = "Lista clientes paginados (pagina/tamanho/nome), ordem nome ASC")
    @GetMapping
    public ApiResponse<PaginaResponse<ClienteResponse>> listar(
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho,
            @RequestParam(required = false) String nome,
            HttpServletRequest http) {
        return ApiResponse.ok(clienteService.listar(pagina, tamanho, nome), http.getRequestURI());
    }

    /** CA-2: detalha cliente por id; inexistente → 404. */
    @Operation(summary = "Detalha cliente por id (404 se inexistente)")
    @GetMapping("/{id}")
    public ApiResponse<ClienteResponse> detalhar(@PathVariable Long id, HttpServletRequest http) {
        return ApiResponse.ok(clienteService.detalhar(id), http.getRequestURI());
    }

    /**
     * CA-4: cria cliente (ADMIN) → 201 com {@link ClienteResponse}. Payload invalido ({@code nome} vazio,
     * {@code email} malformado) → 400. USER → 403; sem token → 401 (SecurityConfig, matchers §3.4).
     */
    @Operation(summary = "Cria cliente (ADMIN) → 201")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ClienteResponse> criar(
            @Valid @RequestBody ClienteRequest request, HttpServletRequest http) {
        return ApiResponse.ok(clienteService.criar(request), http.getRequestURI());
    }

    /**
     * CA-5: atualiza cliente (ADMIN) → 200 com os campos atualizados. Payload invalido → 400; inexistente
     * → 404 (handler local); USER → 403 (SecurityConfig).
     */
    @Operation(summary = "Atualiza cliente (ADMIN) → 200 (404 se inexistente)")
    @PutMapping("/{id}")
    public ApiResponse<ClienteResponse> atualizar(
            @PathVariable Long id,
            @Valid @RequestBody ClienteRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(clienteService.atualizar(id, request), http.getRequestURI());
    }

    /**
     * CA-6: hard delete de cliente (ADMIN) → 204 sem corpo (FC-08). Os produtos permanecem. Inexistente →
     * 404 (handler local); USER → 403 (SecurityConfig). A confirmacao e do front; o back so executa.
     */
    @Operation(summary = "Hard delete de cliente (ADMIN) → 204 (404 se inexistente)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable Long id) {
        clienteService.excluir(id);
    }

    // ---- Handlers locais (nao tocam o GlobalExceptionHandler do M0 — §8) ----------------------

    /** 400 VALIDATION_ERROR para {@code pagina}/{@code tamanho} fora do contrato §3.4 (CA-1). */
    @ExceptionHandler(ParametroPaginacaoInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handlePaginacaoInvalida(
            ParametroPaginacaoInvalidoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(
                ErrorCode.VALIDATION_ERROR.name(), ex.getMessage(), ex.getDetails());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }

    /** 404 NOT_FOUND para id inexistente em GET {@code /{id}} (CA-2). */
    @ExceptionHandler(ClienteNaoEncontradoException.class)
    public ResponseEntity<ApiResponse<Object>> handleNaoEncontrado(
            ClienteNaoEncontradoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.NOT_FOUND.name(), ex.getMessage(), List.of());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
