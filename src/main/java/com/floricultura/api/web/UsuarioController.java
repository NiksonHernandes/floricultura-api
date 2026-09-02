package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.service.EmailJaCadastradoException;
import com.floricultura.api.service.UltimoAdminException;
import com.floricultura.api.service.UsuarioNaoEncontradoException;
import com.floricultura.api.service.UsuarioService;
import com.floricultura.api.web.dto.AlterarStatusRequest;
import com.floricultura.api.web.dto.CriarUsuarioRequest;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.ParametroPaginacaoInvalidoException;
import com.floricultura.api.web.dto.RedefinirSenhaRequest;
import com.floricultura.api.web.dto.UsuarioResponse;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestao de usuarios pelo ADMIN — criar/listar/detalhar (SPEC-M1 §3.1/§3.2, CA-7/CA-8). Toda resposta
 * trafega no envelope {@link ApiResponse} do M0 (§3.1). O acesso {@code ROLE_ADMIN} a
 * {@code /api/v1/usuarios/**} e imposto pelo {@code SecurityConfig} do T-M1-2 (USER→403, ADMIN→200 —
 * provado no {@code AuthSecurityTest}); este controller nao trata seguranca.
 *
 * <ul>
 *   <li>{@code POST /api/v1/usuarios} — 201 com {@link UsuarioResponse} (sem {@code senha_hash}).</li>
 *   <li>{@code GET /api/v1/usuarios} — 200 com {@link PaginaResponse} de {@link UsuarioResponse}
 *       (retrofit M2 — params {@code pagina}/{@code tamanho}/{@code nome}, ordenado {@code nome ASC};
 *       range invalido → 400 no handler local). AD-SQ-29/CA-21.</li>
 *   <li>{@code GET /api/v1/usuarios/{id}} — 200 detalhe; 404 se inexistente.</li>
 *   <li>{@code PATCH /api/v1/usuarios/{id}/status} — 200 com {@link UsuarioResponse} atualizado;
 *       409 ao desativar o unico ADMIN ativo (§4/CA-9); 404 se inexistente.</li>
 *   <li>{@code PATCH /api/v1/usuarios/{id}/senha} — 204 (reset por ADMIN → {@code senha_provisoria=true},
 *       CA-11); 404 se inexistente.</li>
 * </ul>
 *
 * <p>Os {@link ExceptionHandler} <b>locais</b> (precedencia sobre o {@code @RestControllerAdvice} do
 * M0 — que <b>nao</b> e alterado, §8) dao as mensagens amigaveis de 409/404. Nada de senha/hash em
 * log (§9).
 */
@RestController
@RequestMapping("/api/v1/usuarios")
@Tag(name = "usuarios", description = "Gestao de usuarios (ADMIN): criar, listar, detalhar, "
        + "ativar/desativar e reset de senha")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    /** CA-7: cria usuario → 201 com {@link UsuarioResponse} (sem {@code senha_hash}). */
    @Operation(summary = "Cria usuario (role fixa USER) → 201")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UsuarioResponse> criar(
            @Valid @RequestBody CriarUsuarioRequest request, HttpServletRequest http) {
        return ApiResponse.ok(usuarioService.criar(request), http.getRequestURI());
    }

    /**
     * CA-21/AD-SQ-29: lista usuarios paginados (default {@code pagina=0}, {@code tamanho=20}),
     * ordenados por {@code nome ASC}, com filtro opcional {@code nome} ({@code ILIKE '%nome%'}).
     * {@code tamanho} fora de {@code 1..100} ou {@code pagina < 0} → 400 VALIDATION_ERROR (handler
     * local). Retrofit do M2: deixa de devolver array e passa a {@link PaginaResponse}. Nenhum campo
     * de senha e exposto (§9).
     */
    @Operation(summary = "Lista usuarios paginados (pagina/tamanho/nome), ordenados por nome ASC")
    @GetMapping
    public ApiResponse<PaginaResponse<UsuarioResponse>> listar(
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho,
            @RequestParam(required = false) String nome,
            HttpServletRequest http) {
        return ApiResponse.ok(
                usuarioService.listar(pagina, tamanho, nome), http.getRequestURI());
    }

    /** CA-8: detalha usuario por id; inexistente → 404. */
    @Operation(summary = "Detalha usuario por id (404 se inexistente)")
    @GetMapping("/{id}")
    public ApiResponse<UsuarioResponse> detalhar(
            @PathVariable Long id, HttpServletRequest http) {
        return ApiResponse.ok(usuarioService.detalhar(id), http.getRequestURI());
    }

    /**
     * CA-9: ativa/desativa usuario → 200 com {@link UsuarioResponse} atualizado. Desativar o unico
     * ADMIN ativo → 409 (handler local); inexistente → 404.
     */
    @Operation(summary = "Ativa/desativa usuario (409 no ultimo ADMIN ativo; 404 se inexistente)")
    @PatchMapping("/{id}/status")
    public ApiResponse<UsuarioResponse> alterarStatus(
            @PathVariable Long id,
            @Valid @RequestBody AlterarStatusRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(
                usuarioService.alterarStatus(id, request.ativo()), http.getRequestURI());
    }

    /**
     * CA-11: reset de senha pelo ADMIN → 204 sem corpo. Efeito: {@code senha_hash} BCrypt do alvo e
     * {@code senha_provisoria=true}. Inexistente → 404. Nada de senha em log/resposta (§9).
     */
    @Operation(summary = "Reset de senha pelo ADMIN → forca troca no proximo login (204)")
    @PatchMapping("/{id}/senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void redefinirSenha(
            @PathVariable Long id, @Valid @RequestBody RedefinirSenhaRequest request) {
        usuarioService.redefinirSenha(id, request.novaSenha());
    }

    // ---- Handlers locais (nao tocam o GlobalExceptionHandler do M0 — §8) --------------------

    /**
     * 400 VALIDATION_ERROR para {@code pagina}/{@code tamanho} fora do contrato §3.3 (CA-21). Handler
     * <b>local</b> ao {@code UsuarioController} (mesmo do {@code ProdutoController}): sem ele, a
     * {@link ParametroPaginacaoInvalidoException} do helper de paginacao cairia no
     * {@code @RestControllerAdvice} do M0 como 500 — aqui vira 400 com os {@code details} por campo.
     */
    @ExceptionHandler(ParametroPaginacaoInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handlePaginacaoInvalida(
            ParametroPaginacaoInvalidoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(
                ErrorCode.VALIDATION_ERROR.name(), ex.getMessage(), ex.getDetails());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }

    /** 409 CONFLICT ao desativar o unico ADMIN ativo (CA-9). */
    @ExceptionHandler(UltimoAdminException.class)
    public ResponseEntity<ApiResponse<Object>> handleUltimoAdmin(
            UltimoAdminException ex, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.CONFLICT.name(), ex.getMessage(), List.of());
        return ResponseEntity.status(ErrorCode.CONFLICT.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }

    /** 409 CONFLICT com mensagem amigavel "E-mail ja cadastrado." (CA-7). */
    @ExceptionHandler(EmailJaCadastradoException.class)
    public ResponseEntity<ApiResponse<Object>> handleEmailDuplicado(
            EmailJaCadastradoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.CONFLICT.name(), ex.getMessage(), List.of());
        return ResponseEntity.status(ErrorCode.CONFLICT.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }

    /** 404 NOT_FOUND para id inexistente em {@code GET /{id}} (CA-8). */
    @ExceptionHandler(UsuarioNaoEncontradoException.class)
    public ResponseEntity<ApiResponse<Object>> handleNaoEncontrado(
            UsuarioNaoEncontradoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.NOT_FOUND.name(), ex.getMessage(), List.of());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
