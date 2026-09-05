package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.repository.UsuarioRepository;
import com.floricultura.api.service.ContraparteInvalidaException;
import com.floricultura.api.service.EstoqueInsuficienteException;
import com.floricultura.api.service.MovimentacaoService;
import com.floricultura.api.service.ProdutoNaoEncontradoException;
import com.floricultura.api.service.QuantidadeInvalidaException;
import com.floricultura.api.web.dto.MovimentacaoRequest;
import com.floricultura.api.web.dto.MovimentacaoResponse;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.ParametroPaginacaoInvalidoException;
import com.floricultura.api.web.error.ErrorCode;
import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
import com.floricultura.api.web.response.FieldErrorItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Movimentacao de estoque do M2 (T-M2-4, CA-10/CA-11/CA-12/CA-14 — SPEC-M2 §3.1/§3.2/§3.3) sobre o
 * ledger imutavel (AD-SQ-8/AD-SQ-30). Toda resposta trafega no envelope {@link ApiResponse} do M0.
 *
 * <p><b>RBAC (FC-07):</b> {@code POST /produtos/{id}/movimentacoes} exige {@code ROLE_ADMIN} — ja
 * coberto pelo matcher {@code POST /api/v1/produtos/**} do {@code SecurityConfig} (T-M2-3), sem
 * matcher novo. {@code GET /produtos/{id}/movimentacoes} e autenticado (USER+ADMIN) via
 * {@code anyRequest().authenticated()}. USER que faz POST → {@code 403}; sem token → {@code 401}.
 *
 * <ul>
 *   <li>{@code POST /api/v1/produtos/{id}/movimentacoes} — 201 com {@link MovimentacaoResponse}
 *       (ENTRADA soma / SAIDA subtrai / AJUSTE define alvo); 400 na validacao (payload, ENTRADA/SAIDA
 *       com 0, SAIDA &gt; estoque com a mensagem exata); 403 para USER; 404 se o produto nao existe.</li>
 *   <li>{@code GET /api/v1/produtos/{id}/movimentacoes} — 200 com {@link PaginaResponse} ordenado por
 *       {@code criadoEm DESC}; 400 na paginacao; 404 se o produto nao existe.</li>
 * </ul>
 *
 * <p>Os {@link ExceptionHandler} <b>locais</b> (mesmo padrao do {@code ProdutoController}; nao tocam o
 * {@code @RestControllerAdvice} do M0 — §8) traduzem estoque insuficiente / quantidade invalida /
 * paginacao invalida para {@code 400 VALIDATION_ERROR} e produto inexistente para {@code 404}. A
 * validacao de corpo ({@code @Valid}) cai no {@code GlobalExceptionHandler} do M0.
 */
@RestController
@RequestMapping("/api/v1/produtos/{produtoId}/movimentacoes")
@Tag(name = "movimentacoes", description = "Movimentacao de estoque: registrar (ADMIN) "
        + "ENTRADA/SAIDA/AJUSTE e consultar o historico (USER+ADMIN) paginado")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class MovimentacaoController {

    private final MovimentacaoService movimentacaoService;
    private final UsuarioRepository usuarioRepository;

    public MovimentacaoController(
            MovimentacaoService movimentacaoService,
            @Lazy UsuarioRepository usuarioRepository) {
        this.movimentacaoService = movimentacaoService;
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * CA-10/CA-11/CA-12: registra a movimentacao (ADMIN) → 201 com {@link MovimentacaoResponse}
     * ({@code quantidadeResultante} = estoque apos). SAIDA &gt; estoque → 400 "Estoque insuficiente
     * (X em estoque)." sem gravar nada; ENTRADA/SAIDA com 0 → 400. O {@code usuarioId} vem do principal
     * autenticado (nunca do payload — §9). USER → 403 (SecurityConfig).
     */
    @Operation(summary = "Registra movimentacao de estoque (ADMIN): ENTRADA soma, SAIDA subtrai "
            + "(bloqueia se maior que o estoque), AJUSTE define o alvo → 201")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MovimentacaoResponse> movimentar(
            @PathVariable Long produtoId,
            @Valid @RequestBody MovimentacaoRequest request,
            @AuthenticationPrincipal Long usuarioId,
            HttpServletRequest http) {
        // Autor desnormalizado (V7/AD-SQ-45, CA-19): nome resolvido do PRINCIPAL (nunca do payload —
        // §4.5), via projecao escalar, e repassado ao servico como snapshot do ledger.
        String usuarioNome = usuarioId == null ? null : usuarioRepository.findNomeById(usuarioId);
        return ApiResponse.ok(
                movimentacaoService.movimentar(produtoId, request, usuarioId, usuarioNome),
                http.getRequestURI());
    }

    /**
     * CA-14: historico paginado de movimentacoes do produto (USER+ADMIN), ordenado por
     * {@code criadoEm DESC}. Range de paginacao invalido → 400; produto inexistente → 404.
     */
    @Operation(summary = "Lista o historico de movimentacoes do produto (paginado, criadoEm DESC)")
    @GetMapping
    public ApiResponse<PaginaResponse<MovimentacaoResponse>> historico(
            @PathVariable Long produtoId,
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho,
            HttpServletRequest http) {
        return ApiResponse.ok(
                movimentacaoService.historico(produtoId, pagina, tamanho),
                http.getRequestURI());
    }

    // ---- Handlers locais (nao tocam o GlobalExceptionHandler do M0 — §8) ----------------------

    /** 400 VALIDATION_ERROR: SAIDA maior que o estoque (CA-11), com a mensagem e os details exatos. */
    @ExceptionHandler(EstoqueInsuficienteException.class)
    public ResponseEntity<ApiResponse<Object>> handleEstoqueInsuficiente(
            EstoqueInsuficienteException ex, HttpServletRequest http) {
        return respostaValidacao(ex.getMessage(), ex.getDetails(), http);
    }

    /** 400 VALIDATION_ERROR: ENTRADA/SAIDA com quantidade 0 (CA-12). */
    @ExceptionHandler(QuantidadeInvalidaException.class)
    public ResponseEntity<ApiResponse<Object>> handleQuantidadeInvalida(
            QuantidadeInvalidaException ex, HttpServletRequest http) {
        return respostaValidacao(ex.getMessage(), ex.getDetails(), http);
    }

    /**
     * 400 VALIDATION_ERROR: contraparte invalida (V10/AD-SQ-64, R-CA-3/4/5) — tipo errado ou
     * fornecedor/cliente inexistente; {@code details} no campo {@code fornecedorId}/{@code clienteId},
     * nada persiste.
     */
    @ExceptionHandler(ContraparteInvalidaException.class)
    public ResponseEntity<ApiResponse<Object>> handleContraparteInvalida(
            ContraparteInvalidaException ex, HttpServletRequest http) {
        return respostaValidacao(ex.getMessage(), ex.getDetails(), http);
    }

    /** 400 VALIDATION_ERROR: {@code pagina}/{@code tamanho} fora do contrato §3.3 (CA-14). */
    @ExceptionHandler(ParametroPaginacaoInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handlePaginacaoInvalida(
            ParametroPaginacaoInvalidoException ex, HttpServletRequest http) {
        return respostaValidacao(ex.getMessage(), ex.getDetails(), http);
    }

    /** 404 NOT_FOUND: produto inexistente no POST/GET de movimentacoes (CA-14). */
    @ExceptionHandler(ProdutoNaoEncontradoException.class)
    public ResponseEntity<ApiResponse<Object>> handleNaoEncontrado(
            ProdutoNaoEncontradoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.NOT_FOUND.name(), ex.getMessage(), List.of());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }

    private ResponseEntity<ApiResponse<Object>> respostaValidacao(
            String message, List<FieldErrorItem> details, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.VALIDATION_ERROR.name(), message, details);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
