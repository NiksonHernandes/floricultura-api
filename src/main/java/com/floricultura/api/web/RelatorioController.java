package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.service.RelatorioService;
import com.floricultura.api.web.dto.FiltroRelatorio;
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

    public RelatorioController(RelatorioService relatorioService) {
        this.relatorioService = relatorioService;
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
