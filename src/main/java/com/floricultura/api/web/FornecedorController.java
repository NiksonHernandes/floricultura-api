package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.service.FornecedorNaoEncontradoException;
import com.floricultura.api.service.FornecedorService;
import com.floricultura.api.web.dto.ContatoFiltro;
import com.floricultura.api.web.dto.FornecedorRequest;
import com.floricultura.api.web.dto.FornecedorResponse;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.ParametroPaginacaoInvalidoException;
import com.floricultura.api.web.error.ErrorCode;
import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * Leitura de fornecedores do M5 (T-M5-3, CA-1/CA-2/CA-3 — SPEC-M5 §3.4) — irmao de
 * {@code ClienteController}. Toda resposta trafega no envelope {@link ApiResponse} do M0. Reusa o padrao
 * do {@code EventoController} (envelope, {@code PaginacaoParams}, handlers locais, paginacao AD-SQ-29).
 * A escrita (POST/PUT/DELETE) e da T-M5-5.
 *
 * <p><b>RBAC (FC-07):</b> {@code GET /fornecedores/**} e autenticado (USER+ADMIN) via
 * {@code anyRequest().authenticated()} — <b>sem</b> matcher de GET no {@code SecurityConfig}.
 *
 * <ul>
 *   <li>{@code GET /api/v1/fornecedores} — 200 {@link PaginaResponse} (params {@code pagina}/{@code
 *       tamanho}/{@code nome} e, do M6, {@code comTelefone}/{@code comEmail}/{@code ordenarPor}/
 *       {@code direcao}), ordem default {@code nome ASC}, itens com {@code produtoIds=null}; range ou
 *       ordenacao invalidos → 400.</li>
 *   <li>{@code GET /api/v1/fornecedores/{id}} — 200 detalhe; 404 se inexistente.</li>
 *   <li>{@code POST /api/v1/fornecedores} — 201 {@link FornecedorResponse} (ADMIN); 400 validacao; 403
 *       USER; 401 s/ token.</li>
 *   <li>{@code PUT /api/v1/fornecedores/{id}} — 200 atualizado; 400/403; 404 inexistente.</li>
 *   <li>{@code DELETE /api/v1/fornecedores/{id}} — 204 (hard delete); 403 USER; 404 inexistente.</li>
 * </ul>
 *
 * <p><b>Revisao 2026-09-04 (AD-SQ-65):</b> o vinculo fornecedor↔produto deixou de ser junção N:N editavel
 * pelo cadastro; nao ha mais {@code produtoIds} de escrita nem replace-set.
 */
@RestController
@RequestMapping("/api/v1/fornecedores")
@Tag(name = "fornecedores", description = "Cadastro de fornecedores (LGPD): leitura (USER+ADMIN) "
        + "paginada/detalhe e escrita (ADMIN) criar/atualizar/excluir")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class FornecedorController {

    private final FornecedorService fornecedorService;

    public FornecedorController(FornecedorService fornecedorService) {
        this.fornecedorService = fornecedorService;
    }

    /**
     * CA-1: lista fornecedores paginados (default {@code pagina=0}, {@code tamanho=20}), com filtro
     * opcional {@code nome} ({@code ILIKE '%nome%'}). {@code tamanho} fora de {@code 1..100} ou
     * {@code pagina < 0} → 400 VALIDATION_ERROR. Itens com {@code produtoIds=null} (CA-3).
     *
     * <p><b>M6 (SPEC-M6 §3.7, CA-24/CA-25):</b> tri-estados {@code comTelefone}/{@code comEmail}
     * (ausente = sem filtro · {@code true} = tem · {@code false} = nao tem — nulo ou vazio) e ordenacao
     * {@code ordenarPor} ({@code nome}|{@code telefone}|{@code email}, default {@code nome}) /
     * {@code direcao} ({@code asc}|{@code desc}, default {@code asc}); fora do conjunto → 400 com o
     * {@code field} correspondente. Contrato <b>identico</b> ao de {@code /clientes} (D3).
     */
    @Operation(summary = "Lista fornecedores paginados com filtros (nome, comTelefone, comEmail) e "
            + "ordenacao (ordenarPor=nome|telefone|email, direcao=asc|desc; sem telefone/e-mail "
            + "vai para o fim)")
    @GetMapping
    public ApiResponse<PaginaResponse<FornecedorResponse>> listar(
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho,
            @RequestParam(required = false) String nome,
            @Parameter(description = "Tri-estado: ausente = sem filtro; true = tem telefone; "
                    + "false = sem telefone (nulo ou vazio)")
            @RequestParam(required = false) Boolean comTelefone,
            @Parameter(description = "Tri-estado: ausente = sem filtro; true = tem e-mail; "
                    + "false = sem e-mail (nulo ou vazio)")
            @RequestParam(required = false) Boolean comEmail,
            @Parameter(description = "nome | telefone | email (default nome)")
            @RequestParam(required = false) String ordenarPor,
            @Parameter(description = "asc | desc (default asc)")
            @RequestParam(required = false) String direcao,
            HttpServletRequest http) {
        ContatoFiltro filtro = ContatoFiltro.de(nome, comTelefone, comEmail, ordenarPor, direcao);
        return ApiResponse.ok(
                fornecedorService.listar(pagina, tamanho, filtro), http.getRequestURI());
    }

    /** CA-2: detalha fornecedor por id; inexistente → 404. */
    @Operation(summary = "Detalha fornecedor por id (404 se inexistente)")
    @GetMapping("/{id}")
    public ApiResponse<FornecedorResponse> detalhar(
            @PathVariable Long id, HttpServletRequest http) {
        return ApiResponse.ok(fornecedorService.detalhar(id), http.getRequestURI());
    }

    /**
     * CA-4: cria fornecedor (ADMIN) → 201 com {@link FornecedorResponse}. Payload invalido ({@code nome}
     * vazio, {@code email} malformado) → 400. USER → 403; sem token → 401 (SecurityConfig, matchers §3.4).
     */
    @Operation(summary = "Cria fornecedor (ADMIN) → 201")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FornecedorResponse> criar(
            @Valid @RequestBody FornecedorRequest request, HttpServletRequest http) {
        return ApiResponse.ok(fornecedorService.criar(request), http.getRequestURI());
    }

    /**
     * CA-5: atualiza fornecedor (ADMIN) → 200 com os campos atualizados. Payload invalido → 400;
     * inexistente → 404 (handler local); USER → 403 (SecurityConfig).
     */
    @Operation(summary = "Atualiza fornecedor (ADMIN) → 200 (404 se inexistente)")
    @PutMapping("/{id}")
    public ApiResponse<FornecedorResponse> atualizar(
            @PathVariable Long id,
            @Valid @RequestBody FornecedorRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(fornecedorService.atualizar(id, request), http.getRequestURI());
    }

    /**
     * CA-6: hard delete de fornecedor (ADMIN) → 204 sem corpo (FC-08). Os produtos permanecem. Inexistente
     * → 404 (handler local); USER → 403 (SecurityConfig). A confirmacao e do front; o back so executa.
     */
    @Operation(summary = "Hard delete de fornecedor (ADMIN) → 204 (404 se inexistente)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable Long id) {
        fornecedorService.excluir(id);
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
    @ExceptionHandler(FornecedorNaoEncontradoException.class)
    public ResponseEntity<ApiResponse<Object>> handleNaoEncontrado(
            FornecedorNaoEncontradoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.NOT_FOUND.name(), ex.getMessage(), List.of());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
