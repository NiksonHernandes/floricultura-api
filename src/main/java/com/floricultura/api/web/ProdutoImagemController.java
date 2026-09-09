package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.service.ImagemInvalidaException;
import com.floricultura.api.service.ImagemNaoEncontradaException;
import com.floricultura.api.service.ProdutoImagemService;
import com.floricultura.api.service.ProdutoImagemService.ImagemBinario;
import com.floricultura.api.service.ProdutoImagemService.ImagemMetadados;
import com.floricultura.api.service.ProdutoNaoEncontradoException;
import com.floricultura.api.service.TamanhoImagem;
import com.floricultura.api.service.TamanhoImagemInvalidoException;
import com.floricultura.api.web.dto.ProdutoResponse;
import com.floricultura.api.web.error.ErrorCode;
import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
import com.floricultura.api.web.response.FieldErrorItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Enviar, servir e remover a imagem do produto guardada no banco (M3/T-M3-2+T-M3-3 + M5.2/T-M5.2-3/4).
 * O binario e lido/gravado/apagado <b>exclusivamente</b> pelas queries nativas (via {@link
 * ProdutoImagemService}); a {@code @Entity Produto} nunca o materializa (AD-SQ-38).
 *
 * <p><b>RBAC (FC-07 — {@code SecurityConfig} do M2, SEM alteracao):</b> {@code GET .../imagem} cai no
 * {@code anyRequest().authenticated()} (USER+ADMIN); {@code POST}/{@code DELETE .../imagem} casam os
 * matchers {@code POST}/{@code DELETE /api/v1/produtos/**} = {@code hasRole("ADMIN")} — USER → 403,
 * sem token → 401.
 *
 * <ul>
 *   <li>{@code POST /api/v1/produtos/{id}/imagem} — {@code multipart/form-data} parte {@code arquivo}
 *       (ADMIN) → 200 com {@link ProdutoResponse} ({@code temImagem:true}); validacao (vazio/tamanho/
 *       tipo/anti-spoofing/decode) → 400 {@code VALIDATION_ERROR}; inexistente → 404. O upload
 *       decodifica e gera 3 variantes (§3.5).</li>
 *   <li>{@code GET /api/v1/produtos/{id}/imagem?tamanho=thumb|medio|original} — 200 com o binario
 *       <b>fora do envelope</b>; {@code Content-Type} real da variante, {@code Content-Length},
 *       {@code Content-Disposition: inline}, {@code Cache-Control: public, max-age=31536000, immutable}
 *       + {@code ETag} + {@code If-None-Match}→304 (§3.6). Ausente → {@code original}; invalido → 400
 *       {@code field=tamanho}; variante ausente (legado) → fallback ao original; sem imagem/inexistente
 *       → 404 (nunca 401).</li>
 *   <li>{@code DELETE /api/v1/produtos/{id}/imagem} — 204 sem corpo (idempotente); apaga tambem as
 *       variantes (§3.2/C-R12); inexistente → 404; USER → 403.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/produtos/{id}/imagem")
@Tag(name = "produtos-imagem", description = "Servir (USER+ADMIN) e enviar/remover (ADMIN) a imagem do "
        + "produto guardada no banco, com variantes thumb/medio/original (M5.2)")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProdutoImagemController {

    /**
     * {@code Cache-Control} longo e imutavel (1 ano, M5.2/§3.6 — subiu dos 30 dias do M3) — seguro pela
     * URL versionada {@code ?v=} do front. Literal na ordem exata do contrato; emitido cru para nao
     * depender da ordem que o {@code CacheControl} builder do Spring produziria.
     */
    private static final String CACHE_CONTROL_IMAGEM = "public, max-age=31536000, immutable";

    private final ProdutoImagemService imagemService;

    public ProdutoImagemController(ProdutoImagemService imagemService) {
        this.imagemService = imagemService;
    }

    /**
     * CA-2/CA-4/CA-5/CA-6 + M5.2: envia/substitui a imagem (ADMIN) via {@code multipart/form-data}, parte
     * {@code arquivo}. {@code required=false} para o arquivo <b>ausente</b> virar a validacao amigavel
     * (400) em vez de {@code MissingServletRequestPartException}. Sucesso → 200 com {@link ProdutoResponse}
     * ({@code temImagem:true}); inexistente → 404.
     */
    @Operation(summary = "Envia/substitui a imagem do produto (ADMIN), multipart parte 'arquivo' → "
            + "200 com temImagem:true (gera variantes); 400 na validacao; 404 se inexistente")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProdutoResponse> enviar(
            @PathVariable Long id,
            @RequestParam(name = "arquivo", required = false) MultipartFile arquivo,
            HttpServletRequest http) throws IOException {
        return ApiResponse.ok(imagemService.enviar(id, arquivo), http.getRequestURI());
    }

    /**
     * CA-7/CA-8 + M5.2/CA-C4/C5/C6: serve o binario da variante pedida <b>fora do envelope</b>.
     * {@code ?tamanho=thumb|medio|original} (ausente → original; invalido → 400 {@code field=tamanho}).
     * Fallback gracioso ao original quando a variante nao existe (legado). {@code Content-Type} = o real
     * servido. {@code Cache-Control} imutavel de 1 ano + {@code ETag}; {@code If-None-Match} casando →
     * <b>304</b> sem corpo e <b>sem materializar o bytea</b> (so a leitura leve de {@code atualizado_em}).
     */
    @Operation(summary = "Serve a variante da imagem (USER+ADMIN), fora do envelope; ?tamanho=thumb|medio|"
            + "original (default original); ETag/304; 404 se sem imagem ou inexistente")
    @GetMapping(produces = {
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp"})
    public ResponseEntity<byte[]> servir(
            @PathVariable Long id,
            @RequestParam(name = "tamanho", required = false) String tamanho,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        TamanhoImagem alvo = TamanhoImagem.fromWire(tamanho);
        if (alvo == null) {
            throw new TamanhoImagemInvalidoException(); // 400 field=tamanho (CA-C4/C-R10)
        }
        // Leitura leve (SEM bytea): decide 404 + calcula ETag. So no cache-miss carrega o binario.
        ImagemMetadados meta = imagemService.buscarMetadados(id);
        String etag = "\"p" + id + "-" + alvo.wire() + "-" + meta.atualizadoEmEpoch() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_IMAGEM)
                    .build();
        }
        ImagemBinario img = imagemService.buscarBinario(id, alvo);
        byte[] bytes = img.bytes();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.contentType()))
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_IMAGEM)
                .header(HttpHeaders.ETAG, etag)
                .body(bytes);
    }

    /**
     * CA-10 + M5.2/C-R12: remove a imagem do banco (e as variantes) → 204 sem corpo. Idempotente (produto
     * existente sem imagem ainda 204); inexistente → 404 (handler local); USER → 403 (SecurityConfig).
     * Bumpa {@code atualizado_em}, invalidando o cache imutavel via novo {@code ?v=}.
     */
    @Operation(summary = "Remove a imagem do produto (ADMIN) → 204 idempotente (limpa variantes); "
            + "404 se inexistente")
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

    /** 404 NOT_FOUND para {@code POST}/{@code DELETE} em produto inexistente (CA-6/CA-10). */
    @ExceptionHandler(ProdutoNaoEncontradoException.class)
    public ResponseEntity<ApiResponse<Object>> handleProdutoNaoEncontrado(
            ProdutoNaoEncontradoException ex, HttpServletRequest http) {
        return naoEncontrado(ex.getMessage(), http);
    }

    /** 400 VALIDATION_ERROR para upload reprovado (vazio/tamanho/tipo/spoof/decode) — CA-4/CA-5/CA-C8. */
    @ExceptionHandler(ImagemInvalidaException.class)
    public ResponseEntity<ApiResponse<Object>> handleImagemInvalida(
            ImagemInvalidaException ex, HttpServletRequest http) {
        return validacaoCampo(ImagemInvalidaException.CAMPO, ex.getMessage(), http);
    }

    /** 400 VALIDATION_ERROR para {@code ?tamanho=} fora do enum (M5.2/CA-C4/C-R10) — {@code field=tamanho}. */
    @ExceptionHandler(TamanhoImagemInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handleTamanhoInvalido(
            TamanhoImagemInvalidoException ex, HttpServletRequest http) {
        return validacaoCampo(TamanhoImagemInvalidoException.CAMPO, ex.getMessage(), http);
    }

    /**
     * 400 VALIDATION_ERROR quando o upload estoura o teto do container (> 6MB, §12) — defesa alem do
     * limite de negocio (2 MB, M5.2/§3.7). Capturavel aqui por causa do {@code multipart.resolve-lazily=true}.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleTamanhoExcedido(
            MaxUploadSizeExceededException ex, HttpServletRequest http) {
        return validacaoCampo(ImagemInvalidaException.CAMPO,
                "Imagem excede o tamanho maximo de 2 MB.", http);
    }

    private ResponseEntity<ApiResponse<Object>> naoEncontrado(
            String mensagem, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.NOT_FOUND.name(), mensagem, List.of());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }

    private ResponseEntity<ApiResponse<Object>> validacaoCampo(
            String campo, String mensagem, HttpServletRequest http) {
        ApiError error = new ApiError(ErrorCode.VALIDATION_ERROR.name(), mensagem,
                List.of(new FieldErrorItem(campo, mensagem)));
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
