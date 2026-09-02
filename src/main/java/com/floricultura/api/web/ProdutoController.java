package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.service.ProdutoNaoEncontradoException;
import com.floricultura.api.service.ProdutoService;
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
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leitura de produtos do M2 — lista paginada (+ filtro por nome) e detalhe (SPEC-M2 §3.1/§3.2/§3.3,
 * CA-2/CA-3/CA-4/CA-5/CA-15). Toda resposta trafega no envelope {@link ApiResponse} do M0 (§3.1).
 *
 * <p><b>RBAC (FC-07):</b> {@code GET /produtos/**} e autenticado (USER+ADMIN) — imposto pelo
 * {@code SecurityConfig} via {@code anyRequest().authenticated()}; sem token → {@code 401}. A escrita
 * (POST/PUT/DELETE, ADMIN) chega em T-M2-3; este controller so faz leitura.
 *
 * <ul>
 *   <li>{@code GET /api/v1/produtos} — 200 com {@link PaginaResponse} de {@link ProdutoResponse}
 *       (params {@code pagina}/{@code tamanho}/{@code nome}); range invalido → 400.</li>
 *   <li>{@code GET /api/v1/produtos/{id}} — 200 detalhe; 404 se inexistente.</li>
 * </ul>
 *
 * <p>Os {@link ExceptionHandler} <b>locais</b> (precedencia sobre o {@code @RestControllerAdvice} do
 * M0 — que <b>nao</b> e alterado, §8) traduzem a paginacao invalida (400) e o produto inexistente
 * (404), no mesmo padrao dos handlers locais do {@code UsuarioController}.
 */
@RestController
@RequestMapping("/api/v1/produtos")
@Tag(name = "produtos", description = "Leitura de produtos (USER+ADMIN): listar paginado com filtro "
        + "por nome e detalhar por id")
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
