package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.repository.ProdutoImagemProjection;
import com.floricultura.api.service.ImagemNaoEncontradaException;
import com.floricultura.api.service.ProdutoImagemService;
import com.floricultura.api.service.ProdutoNaoEncontradoException;
import com.floricultura.api.web.error.ErrorCode;
import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serve e remove a imagem do produto guardada no banco (M3/T-M3-2, CA-7/CA-8/CA-10) — SPEC-M3 §3.3.
 * O binario e lido/apagado <b>exclusivamente</b> pelas queries nativas da T-M3-1 (via
 * {@link ProdutoImagemService}); a {@code @Entity Produto} nunca o materializa (AD-SQ-38). O upload
 * ({@code POST .../imagem}) e a validacao/anti-spoofing sao da T-M3-3 — nao entram aqui.
 *
 * <p><b>RBAC (FC-07 — {@code SecurityConfig} do M2, SEM alteracao):</b> {@code GET .../imagem} cai no
 * {@code anyRequest().authenticated()} (USER+ADMIN); {@code DELETE .../imagem} casa o matcher
 * {@code DELETE /api/v1/produtos/**} = {@code hasRole("ADMIN")} — USER → 403, sem token → 401.
 *
 * <ul>
 *   <li>{@code GET /api/v1/produtos/{id}/imagem} — 200 com o binario <b>fora do envelope</b>
 *       ({@code ResponseEntity<byte[]>}); {@code Content-Type} gravado, {@code Content-Length},
 *       {@code Content-Disposition: inline}, {@code Cache-Control: public, max-age=2592000,
 *       immutable}. Sem imagem ou produto inexistente → <b>404</b> (nunca 401 — nao desloga o front).
 *       O parametro de cache-busting {@code ?v=} do front (§3.3) e <b>ignorado</b> pelo endpoint.</li>
 *   <li>{@code DELETE /api/v1/produtos/{id}/imagem} — 204 sem corpo (idempotente: produto existente
 *       sem imagem ainda 204); inexistente → 404; USER → 403.</li>
 * </ul>
 *
 * <p>Os {@link ExceptionHandler} locais traduzem os 404 (produto/imagem inexistente) no envelope §3.1,
 * no mesmo padrao do {@code ProdutoController} — o {@code @RestControllerAdvice} do M0 nao e tocado.
 */
@RestController
@RequestMapping("/api/v1/produtos/{id}/imagem")
@Tag(name = "produtos-imagem", description = "Servir (USER+ADMIN) e remover (ADMIN) a imagem do "
        + "produto guardada no banco; upload em T-M3-3")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProdutoImagemController {

    /**
     * {@code Cache-Control} longo e imutavel — seguro pela URL versionada {@code ?v=} do front (§3.3).
     * Literal na ordem exata do contrato ({@code public, max-age=2592000, immutable} = 30 dias, CA-7);
     * emitido cru para nao depender da ordem que o {@code CacheControl} builder do Spring produziria.
     */
    private static final String CACHE_CONTROL_IMAGEM = "public, max-age=2592000, immutable";

    private final ProdutoImagemService imagemService;

    public ProdutoImagemController(ProdutoImagemService imagemService) {
        this.imagemService = imagemService;
    }

    /**
     * CA-7/CA-8: serve o binario da imagem <b>fora do envelope</b>. {@code Content-Type} = o tipo
     * gravado (sempre jpeg/png/webp — whitelist da V5), {@code Content-Length}, {@code
     * Content-Disposition: inline} e {@code Cache-Control} imutavel de 30 dias. Sem imagem/produto
     * inexistente → 404 (handler local). {@code produces} documenta {@code image/*} no Swagger; o
     * {@code Content-Type} real e definido em runtime.
     */
    @Operation(summary = "Serve o binario da imagem do produto (USER+ADMIN), fora do envelope; "
            + "404 se sem imagem ou inexistente")
    @GetMapping(produces = {
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp"})
    public ResponseEntity<byte[]> servir(@PathVariable Long id) {
        ProdutoImagemProjection imagem = imagemService.buscarImagem(id);
        byte[] bytes = imagem.getImagem();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(imagem.getImagemContentType()))
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_IMAGEM)
                .body(bytes);
    }

    /**
     * CA-10: remove a imagem do banco → 204 sem corpo. Idempotente (produto existente sem imagem
     * ainda 204); inexistente → 404 (handler local); USER → 403 (SecurityConfig). Bumpa
     * {@code atualizado_em} (query nativa), invalidando o cache imutavel via novo {@code ?v=}.
     */
    @Operation(summary = "Remove a imagem do produto (ADMIN) → 204 idempotente; 404 se inexistente")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover(@PathVariable Long id) {
        imagemService.remover(id);
    }

    // ---- Handlers locais (nao tocam o GlobalExceptionHandler do M0 — §8) ----------------------

    /** 404 NOT_FOUND para {@code GET} sem imagem ou produto inexistente (CA-8) — nunca 401. */
    @ExceptionHandler(ImagemNaoEncontradaException.class)
    public ResponseEntity<ApiResponse<Object>> handleImagemNaoEncontrada(
            ImagemNaoEncontradaException ex, HttpServletRequest http) {
        return naoEncontrado(ex.getMessage(), http);
    }

    /** 404 NOT_FOUND para {@code DELETE} em produto inexistente (CA-10). */
    @ExceptionHandler(ProdutoNaoEncontradoException.class)
    public ResponseEntity<ApiResponse<Object>> handleProdutoNaoEncontrado(
            ProdutoNaoEncontradoException ex, HttpServletRequest http) {
        return naoEncontrado(ex.getMessage(), http);
    }

    private ResponseEntity<ApiResponse<Object>> naoEncontrado(
            String mensagem, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.NOT_FOUND.name(), mensagem, List.of());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
