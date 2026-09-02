package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.service.ProdutoNaoEncontradoException;
import com.floricultura.api.service.ProdutoService;
import com.floricultura.api.web.dto.AtualizarProdutoRequest;
import com.floricultura.api.web.dto.CriarProdutoRequest;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.ParametroPaginacaoInvalidoException;
import com.floricultura.api.web.dto.ProdutoResponse;
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
 * CRUD de produtos do M2 — leitura (lista paginada + filtro / detalhe, CA-2/CA-3/CA-4/CA-5/CA-15) e
 * escrita (criar/atualizar/deletar, T-M2-3, CA-6/CA-7/CA-8/CA-9) — SPEC-M2 §3.1/§3.2/§3.3. Toda
 * resposta trafega no envelope {@link ApiResponse} do M0 (§3.1).
 *
 * <p><b>RBAC (FC-07):</b> {@code GET /produtos/**} e autenticado (USER+ADMIN) via
 * {@code anyRequest().authenticated()}; {@code POST}/{@code PUT}/{@code DELETE} de {@code /produtos/**}
 * exigem {@code ROLE_ADMIN} (matchers por metodo no {@code SecurityConfig}) — USER → {@code 403}, sem
 * token → {@code 401}. A autorizacao e imposta no servidor, nunca so no controller.
 *
 * <ul>
 *   <li>{@code GET /api/v1/produtos} — 200 com {@link PaginaResponse} de {@link ProdutoResponse}
 *       (params {@code pagina}/{@code tamanho}/{@code nome}); range invalido → 400.</li>
 *   <li>{@code GET /api/v1/produtos/{id}} — 200 detalhe; 404 se inexistente.</li>
 *   <li>{@code POST /api/v1/produtos} — 201 com {@link ProdutoResponse} ({@code estoqueAtual=0}); 400
 *       na validacao; 403 para USER.</li>
 *   <li>{@code PUT /api/v1/produtos/{id}} — 200 atualizado ({@code estoqueAtual} inalterado); 400/403;
 *       404 se inexistente.</li>
 *   <li>{@code DELETE /api/v1/produtos/{id}} — 204 (hard delete, FC-08); 403 para USER; 404 se
 *       inexistente.</li>
 * </ul>
 *
 * <p>Os {@link ExceptionHandler} <b>locais</b> (precedencia sobre o {@code @RestControllerAdvice} do
 * M0 — que <b>nao</b> e alterado, §8) traduzem a paginacao invalida (400) e o produto inexistente
 * (404), no mesmo padrao dos handlers locais do {@code UsuarioController}. A validacao de corpo
 * (@Valid) cai no {@code GlobalExceptionHandler} do M0 (400 com {@code details} por campo).
 */
@RestController
@RequestMapping("/api/v1/produtos")
@Tag(name = "produtos", description = "CRUD de produtos: leitura (USER+ADMIN) paginada/detalhe e "
        + "escrita (ADMIN) criar/atualizar/deletar")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProdutoController {

    private final ProdutoService produtoService;

    public ProdutoController(ProdutoService produtoService) {
        this.produtoService = produtoService;
    }

    /**
     * CA-2/CA-3/CA-4: lista produtos paginados (default {@code pagina=0}, {@code tamanho=20}),
     * ordenados por {@code nome ASC}, com filtro opcional {@code nome} ({@code ILIKE '%nome%'}).
     * {@code tamanho} fora de {@code 1..100} ou {@code pagina < 0} → 400 VALIDATION_ERROR.
     */
    @Operation(summary = "Lista produtos paginados (pagina/tamanho/nome), ordenados por nome ASC")
    @GetMapping
    public ApiResponse<PaginaResponse<ProdutoResponse>> listar(
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho,
            @RequestParam(required = false) String nome,
            HttpServletRequest http) {
        return ApiResponse.ok(
                produtoService.listar(pagina, tamanho, nome), http.getRequestURI());
    }

    /** CA-5/CA-15: detalha produto por id (com {@code estoqueBaixo}); inexistente → 404. */
    @Operation(summary = "Detalha produto por id, com estoqueBaixo computado (404 se inexistente)")
    @GetMapping("/{id}")
    public ApiResponse<ProdutoResponse> detalhar(
            @PathVariable Long id, HttpServletRequest http) {
        return ApiResponse.ok(produtoService.detalhar(id), http.getRequestURI());
    }

    /**
     * CA-6/CA-9: cria produto (ADMIN) → 201 com {@link ProdutoResponse} e {@code estoqueAtual=0}
     * (AD-SQ-30). Payload invalido (enum fora do conjunto, {@code nome} vazio, {@code estoqueMinimo}/
     * {@code preco} negativo) → 400 VALIDATION_ERROR com {@code details}. USER → 403 (SecurityConfig).
     */
    @Operation(summary = "Cria produto (ADMIN) → 201 com estoqueAtual=0")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProdutoResponse> criar(
            @Valid @RequestBody CriarProdutoRequest request, HttpServletRequest http) {
        return ApiResponse.ok(produtoService.criar(request), http.getRequestURI());
    }

    /**
     * CA-7/CA-9: atualiza produto (ADMIN) → 200 com o produto atualizado e {@code estoqueAtual}
     * <b>inalterado</b> (AD-SQ-30). Payload invalido → 400; inexistente → 404 (handler local); USER →
     * 403 (SecurityConfig).
     */
    @Operation(summary = "Atualiza produto (ADMIN) → 200; nunca toca estoqueAtual (404 se inexistente)")
    @PutMapping("/{id}")
    public ApiResponse<ProdutoResponse> atualizar(
            @PathVariable Long id,
            @Valid @RequestBody AtualizarProdutoRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(produtoService.atualizar(id, request), http.getRequestURI());
    }

    /**
     * CA-8: hard delete de produto (ADMIN) → 204 sem corpo (FC-08, IRREVERSIVEL). O ledger sobrevive
     * com {@code produto_id=NULL} e {@code produto_nome} preservado (V4/AD-SQ-34). Inexistente → 404
     * (handler local); USER → 403 (SecurityConfig). A confirmacao e do front; o back so executa.
     */
    @Operation(summary = "Hard delete de produto (ADMIN) → 204 (404 se inexistente)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletar(@PathVariable Long id) {
        produtoService.deletar(id);
    }

    // ---- Handlers locais (nao tocam o GlobalExceptionHandler do M0 — §8) ----------------------

    /** 400 VALIDATION_ERROR para {@code pagina}/{@code tamanho} fora do contrato §3.3 (CA-3). */
    @ExceptionHandler(ParametroPaginacaoInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handlePaginacaoInvalida(
            ParametroPaginacaoInvalidoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(
                ErrorCode.VALIDATION_ERROR.name(), ex.getMessage(), ex.getDetails());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }

    /** 404 NOT_FOUND para id inexistente em {@code GET /{id}} (CA-5). */
    @ExceptionHandler(ProdutoNaoEncontradoException.class)
    public ResponseEntity<ApiResponse<Object>> handleNaoEncontrado(
            ProdutoNaoEncontradoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.NOT_FOUND.name(), ex.getMessage(), List.of());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
