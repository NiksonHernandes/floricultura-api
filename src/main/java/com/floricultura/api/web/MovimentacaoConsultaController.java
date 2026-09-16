package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.service.MovimentacaoService;
import com.floricultura.api.web.dto.FiltroMovimentacao;
import com.floricultura.api.web.dto.MovimentacaoResponse;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.ParametroPaginacaoInvalidoException;
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
 * Lista GLOBAL de movimentacoes do M4 (T-M4-10, CA-22/CA-23/CA-24 — SPEC-M4 §3.5). Primeira visao
 * consolidada do ledger imutavel (paga a divida de auditoria do canon), legivel tambem pelo USER. Toda
 * resposta trafega no envelope {@link ApiResponse} do M0.
 *
 * <p><b>RBAC (FC-07):</b> {@code GET /api/v1/movimentacoes} e autenticado (USER+ADMIN) via
 * {@code anyRequest().authenticated()} — <b>nenhum matcher novo</b> no {@code SecurityConfig} (e so
 * GET). Sem token → {@code 401}.
 *
 * <ul>
 *   <li>{@code GET /api/v1/movimentacoes?pagina&tamanho&q} — 200 com {@link PaginaResponse} ordenado
 *       por {@code criado_em DESC}; filtro {@code q} opcional casa {@code produto_nome} <b>ou</b>
 *       {@code usuario_nome} ({@code ILIKE '%q%'}); range de paginacao invalido → 400.</li>
 * </ul>
 *
 * <p>Separado do {@code MovimentacaoController} (que e {@code /produtos/{id}/movimentacoes}) por ser um
 * recurso de rota distinta ({@code /movimentacoes}); reusa o mesmo {@code MovimentacaoService} e o
 * handler local de paginacao invalida (padrao dos demais controllers).
 */
@RestController
@RequestMapping("/api/v1/movimentacoes")
@Tag(name = "movimentacoes-global", description = "Lista global do ledger de movimentacoes "
        + "(USER+ADMIN), paginada, filtro por produto ou autor, ordem criado_em DESC")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class MovimentacaoConsultaController {

    private final MovimentacaoService movimentacaoService;

    public MovimentacaoConsultaController(MovimentacaoService movimentacaoService) {
        this.movimentacaoService = movimentacaoService;
    }

    /**
     * CA-22/CA-23: lista global paginada (default {@code pagina=0}, {@code tamanho=20}), ordem
     * {@code criado_em DESC}, com filtro opcional {@code q} ({@code produto_nome} OU {@code usuario_nome}
     * por {@code ILIKE '%q%'}). {@code tamanho} fora de {@code 1..100} ou {@code pagina < 0} → 400.
     *
     * <p><b>M7/T-M7-03 (§3.5, CA-16..CA-19):</b> acrescenta {@code de}, {@code ate}, {@code tipo},
     * {@code produtoId}, {@code clienteId} e {@code fornecedorId} — <b>todos opcionais</b>, com
     * semantica <b>E</b> entre parametros diferentes. Omitir todos devolve a resposta de hoje, byte a
     * byte (CA-16). {@code de}/{@code ate} sao {@code yyyy-MM-dd} de negocio, ancorados em
     * {@code America/Sao_Paulo} pelo {@link FiltroMovimentacao} (o dia {@code ate} entra <b>inteiro</b>);
     * texto que nao e data e {@code produtoId=abc} caem no 400 de tipo do
     * {@code GlobalExceptionHandler} (M6.1/D3), que nao pode regredir. A ordem continua
     * {@code criado_em DESC} fixa — nao ha parametro de ordenacao (fora de escopo, §2).
     */
    @Operation(summary = "Lista global de movimentacoes (pagina/tamanho/q + filtros de periodo, "
            + "tipo, produto e contraparte), ordem criado_em DESC")
    @GetMapping
    public ApiResponse<PaginaResponse<MovimentacaoResponse>> listar(
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) Long produtoId,
            @RequestParam(required = false) Long clienteId,
            @RequestParam(required = false) Long fornecedorId,
            HttpServletRequest http) {
        FiltroMovimentacao filtro =
                FiltroMovimentacao.de(q, de, ate, tipo, produtoId, clienteId, fornecedorId);
        return ApiResponse.ok(
                movimentacaoService.buscarGlobal(pagina, tamanho, filtro), http.getRequestURI());
    }

    // ---- Handler local (nao toca o GlobalExceptionHandler do M0 — §8) -------------------------

    /** 400 VALIDATION_ERROR para {@code pagina}/{@code tamanho} fora do contrato §3.3 (CA-23). */
    @ExceptionHandler(ParametroPaginacaoInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handlePaginacaoInvalida(
            ParametroPaginacaoInvalidoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(
                ErrorCode.VALIDATION_ERROR.name(), ex.getMessage(), ex.getDetails());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
