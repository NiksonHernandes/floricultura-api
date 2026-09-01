package com.floricultura.api.web;

import com.floricultura.api.service.AuthService;
import com.floricultura.api.service.CredenciaisInvalidasException;
import com.floricultura.api.service.SenhaAtualIncorretaException;
import com.floricultura.api.web.dto.LoginRequest;
import com.floricultura.api.web.dto.LoginResponse;
import com.floricultura.api.web.dto.MeResponse;
import com.floricultura.api.web.dto.TrocarSenhaRequest;
import com.floricultura.api.web.error.ErrorCode;
import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
import com.floricultura.api.web.response.FieldErrorItem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de autenticacao (SPEC-M1 §3.1/§3.2, CA-1/CA-2/CA-3/CA-4/CA-10). Toda resposta trafega no
 * envelope {@link ApiResponse} do M0 (§3.1).
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/login} — publico (matcher do {@code SecurityConfig}); 200 com token.</li>
 *   <li>{@code GET /api/v1/auth/me} — autenticado; perfil do {@code @AuthenticationPrincipal} (id).</li>
 *   <li>{@code PATCH /api/v1/auth/senha} — autenticado; 204 na troca da propria senha.</li>
 * </ul>
 *
 * <p>Os dois {@link ExceptionHandler} <b>locais</b> (escopo deste controller, precedencia sobre o
 * {@code @RestControllerAdvice} do M0 — que <b>nao</b> e alterado, §8) traduzem as falhas de auth:
 * {@link CredenciaisInvalidasException}→401 <b>generico identico</b> (anti-enumeracao, CA-2/CA-3) e
 * {@link SenhaAtualIncorretaException}→400 campo {@code senhaAtual} (CA-10). Nada de senha/token em
 * log (§9).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** CA-1: login OK → 200 com token + sessao (sem {@code senha_hash}). */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authService.autenticar(request), http.getRequestURI());
    }

    /** CA-4: perfil do autenticado (id vem do principal populado pelo filtro JWT). */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(
            @AuthenticationPrincipal Long usuarioId, HttpServletRequest http) {
        return ApiResponse.ok(authService.perfil(usuarioId), http.getRequestURI());
    }

    /** CA-10: troca da propria senha → 204 sem corpo. */
    @PatchMapping("/senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trocarSenha(
            @AuthenticationPrincipal Long usuarioId, @Valid @RequestBody TrocarSenhaRequest request) {
        authService.trocarSenha(usuarioId, request);
    }

    // ---- Handlers locais (nao tocam o GlobalExceptionHandler do M0 — §8) --------------------

    /**
     * 401 UNAUTHORIZED generico (CA-2/CA-3): corpo fixo e identico para os tres casos de falha de
     * login (inexistente/senha errada/inativo) — anti-enumeracao. Sem token no corpo.
     */
    @ExceptionHandler(CredenciaisInvalidasException.class)
    public ResponseEntity<ApiResponse<Object>> handleCredenciaisInvalidas(HttpServletRequest http) {
        ApiError error = new ApiError(
                ErrorCode.UNAUTHORIZED.name(), "Credenciais invalidas.", List.of());
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }

    /** 400 VALIDATION_ERROR no campo {@code senhaAtual} (CA-10), sem deslogar. */
    @ExceptionHandler(SenhaAtualIncorretaException.class)
    public ResponseEntity<ApiResponse<Object>> handleSenhaAtualIncorreta(HttpServletRequest http) {
        ApiError error = new ApiError(
                ErrorCode.VALIDATION_ERROR.name(),
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                List.of(new FieldErrorItem("senhaAtual", "senha atual incorreta")));
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
