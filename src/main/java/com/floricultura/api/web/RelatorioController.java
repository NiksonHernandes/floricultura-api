package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.service.RelatorioExportService;
import com.floricultura.api.service.RelatorioExportService.Arquivo;
import com.floricultura.api.service.RelatorioService;
import com.floricultura.api.web.dto.FiltroRelatorio;
import com.floricultura.api.web.dto.FormatoExport;
import com.floricultura.api.web.dto.ParametroPaginacaoInvalidoException;
import com.floricultura.api.web.dto.RelatorioResponse;
import com.floricultura.api.web.error.ErrorCode;
import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatorio de movimentacoes do M7 (T-M7-04, SPEC-M7 §3.7 — CA-20..CA-25, CA-54, CA-55): agregacao
 * por SEMANA/MES sobre um intervalo livre, entradas x saidas x ajustes, no envelope
 * {@link ApiResponse}.
 *
 * <p><b>RBAC (AD-SQ-158):</b> {@code /api/v1/relatorios/**} e o <b>primeiro GET ADMIN-only do
 * projeto</b> — todos os demais GET caem em {@code anyRequest().authenticated()}. O matcher vive no
 * {@code SecurityConfig} (§3.9); sem ele um USER leria nome de cliente/fornecedor, que e dado pessoal
 * (§9, LGPD). USER → 403, sem token → 401.
 *
 * <p><b>{@code de}/{@code ate} sao {@code required = false} de proposito</b> (§3.7-a0, CA-54): o
 * {@code GlobalExceptionHandler} nao trata {@code MissingServletRequestParameterException}, entao
 * declarar o parametro obrigatorio no Spring devolveria <b>500</b> para um erro de cliente. A
 * obrigatoriedade e do {@link FiltroRelatorio}, que responde 400 {@code VALIDATION_ERROR} com
 * {@code field}.
 */
@RestController
@RequestMapping("/api/v1/relatorios")
@Tag(name = "relatorios", description = "Relatorio agregado do ledger de movimentacoes "
        + "(somente ADMIN): entradas x saidas por SEMANA ou MES, com totais em R$")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class RelatorioController {

    private final RelatorioService relatorioService;
    private final RelatorioExportService relatorioExportService;

    public RelatorioController(
            RelatorioService relatorioService, RelatorioExportService relatorioExportService) {
        this.relatorioService = relatorioService;
        this.relatorioExportService = relatorioExportService;
    }

    /**
     * CA-20..CA-25: 200 com {@code resumo} + serie continua de {@code periodos}. {@code de}/{@code ate}
     * sao obrigatorios ({@code yyyy-MM-dd}, {@code America/Sao_Paulo}, dia final inteiro);
     * {@code granularidade} default {@code MES}; {@code tipo}/{@code produtoId}/{@code clienteId}/
     * {@code fornecedorId} opcionais, com semantica <b>E</b> (§3.5). Intervalo acima de 366 dias,
     * {@code de > ate}, {@code tipo}/{@code granularidade} fora do conjunto e periodo ausente ⇒ 400
     * com {@code details[].field}.
     */
    @Operation(summary = "Relatorio agregado de movimentacoes por periodo (ADMIN): resumo em R$ e "
            + "serie continua de baldes por SEMANA ou MES")
    @GetMapping("/movimentacoes")
    public ApiResponse<RelatorioResponse> movimentacoes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String granularidade,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) Long produtoId,
            @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) Long fornecedorId,
            HttpServletRequest http) {
        FiltroRelatorio filtro = FiltroRelatorio.de(
                de, ate, granularidade, tipo, produtoId, clienteId, fornecedorId);
        return ApiResponse.ok(relatorioService.gerar(filtro), http.getRequestURI());
    }

    /**
     * CA-26..CA-30: o <b>mesmo</b> recorte do §3.7 baixado como arquivo — binario <b>fora do
     * envelope</b> (precedente do endpoint de imagem, AD-SQ-37).
     *
     * <p><b>A validacao e a MESMA, nao uma copia</b> (CA-29/CA-54): esta rota chama a mesma
     * {@link FiltroRelatorio#de} da rota de agregacao, entao herda a precedencia da AD-SQ-170
     * (presenca de {@code de}/{@code ate} → janela → teto de 366 dias → {@code granularidade}) e as
     * mensagens palavra por palavra. O {@code formato} e validado <b>depois</b> do periodo, porque a
     * CA-54 exige que {@code /export} sem {@code de} responda {@code field=de}: sem periodo nao ha o
     * que exportar, e apontar o erro menos importante custa duas viagens ao operador.
     *
     * <p><b>{@code Cache-Control: no-store}</b> (CA-28): o arquivo carrega nome de cliente/fornecedor
     * — dado pessoal (§9/LGPD) que nao pode ficar em cache de proxy ou de navegador. Nada e gravado em
     * disco no servidor.
     *
     * <p><b>O {@code produces} binario nao impede o 400 em JSON</b> — o
     * {@code ExceptionHandlerExceptionResolver} limpa os tipos produziveis antes do handler local.
     * Nao e suposicao: o {@code GET /produtos/{id}/imagem} ja e assim, e o
     * {@code ProdutoImagemProcessamentoTest} prova o 400 {@code VALIDATION_ERROR} naquela rota.
     */
    @Operation(summary = "Exporta o relatorio de movimentacoes como arquivo (ADMIN): "
            + "?formato=PDF|XLSX, binario fora do envelope, attachment + Cache-Control no-store")
    @GetMapping(value = "/movimentacoes/export",
            produces = {MediaType.APPLICATION_PDF_VALUE, FormatoExport.CONTENT_TYPE_XLSX})
    public ResponseEntity<byte[]> exportar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String granularidade,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) Long produtoId,
            @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) Long fornecedorId,
            @RequestParam(required = false) String formato) {
        FiltroRelatorio filtro = FiltroRelatorio.de(
                de, ate, granularidade, tipo, produtoId, clienteId, fornecedorId);
        Arquivo arquivo = relatorioExportService.gerar(filtro, FormatoExport.fromWire(formato));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(arquivo.contentType()))
                .contentLength(arquivo.conteudo().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + arquivo.nome() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(arquivo.conteudo());
    }

    // ---- Handler local (nao toca o GlobalExceptionHandler do M0 — §8) -------------------------

    /** 400 VALIDATION_ERROR dos parametros do §3.7 (CA-25, CA-54) — mesmo formato do §3.5. */
    @ExceptionHandler(ParametroPaginacaoInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handleParametroInvalido(
            ParametroPaginacaoInvalidoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(
                ErrorCode.VALIDATION_ERROR.name(), ex.getMessage(), ex.getDetails());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
