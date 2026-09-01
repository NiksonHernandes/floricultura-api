package com.floricultura.api.web;

import com.floricultura.api.service.EmailJaCadastradoException;
import com.floricultura.api.service.UltimoAdminException;
import com.floricultura.api.service.UsuarioNaoEncontradoException;
import com.floricultura.api.service.UsuarioService;
import com.floricultura.api.web.dto.AlterarStatusRequest;
import com.floricultura.api.web.dto.CriarUsuarioRequest;
import com.floricultura.api.web.dto.RedefinirSenhaRequest;
import com.floricultura.api.web.dto.UsuarioResponse;
import com.floricultura.api.web.error.ErrorCode;
import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
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
 *   <li>{@code GET /api/v1/usuarios} — 200 lista ordenada por {@code nome}.</li>
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
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    /** CA-7: cria usuario → 201 com {@link UsuarioResponse} (sem {@code senha_hash}). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UsuarioResponse> criar(
            @Valid @RequestBody CriarUsuarioRequest request, HttpServletRequest http) {
        return ApiResponse.ok(usuarioService.criar(request), http.getRequestURI());
    }

    /** CA-8: lista usuarios (ordenada por {@code nome}), nenhum campo de senha exposto. */
    @GetMapping
    public ApiResponse<List<UsuarioResponse>> listar(HttpServletRequest http) {
        return ApiResponse.ok(usuarioService.listar(), http.getRequestURI());
    }

    /** CA-8: detalha usuario por id; inexistente → 404. */
    @GetMapping("/{id}")
    public ApiResponse<UsuarioResponse> detalhar(
            @PathVariable Long id, HttpServletRequest http) {
        return ApiResponse.ok(usuarioService.detalhar(id), http.getRequestURI());
    }

    /**
     * CA-9: ativa/desativa usuario → 200 com {@link UsuarioResponse} atualizado. Desativar o unico
     * ADMIN ativo → 409 (handler local); inexistente → 404.
     */
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
    @PatchMapping("/{id}/senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void redefinirSenha(
            @PathVariable Long id, @Valid @RequestBody RedefinirSenhaRequest request) {
        usuarioService.redefinirSenha(id, request.novaSenha());
    }

    // ---- Handlers locais (nao tocam o GlobalExceptionHandler do M0 — §8) --------------------

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
