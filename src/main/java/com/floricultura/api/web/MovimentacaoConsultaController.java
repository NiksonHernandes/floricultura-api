package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.repository.UsuarioRepository;
import com.floricultura.api.service.EstoqueInsuficienteException;
import com.floricultura.api.service.EstornoInvalidoException;
import com.floricultura.api.service.MovimentacaoService;
import com.floricultura.api.web.dto.EstornoRequest;
import com.floricultura.api.web.dto.FiltroMovimentacao;
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
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
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

    private static final Logger log = LoggerFactory.getLogger(MovimentacaoConsultaController.class);

    /** Nome do indice unico parcial da V13 que fecha a corrida de dois estornos (SPEC-M7 §3.1-b). */
    private static final String UX_MOV_ESTORNO = "ux_mov_estorno";

    private final MovimentacaoService movimentacaoService;
    private final UsuarioRepository usuarioRepository;

    public MovimentacaoConsultaController(
            MovimentacaoService movimentacaoService,
            @Lazy UsuarioRepository usuarioRepository) {
        this.movimentacaoService = movimentacaoService;
        this.usuarioRepository = usuarioRepository;
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

    /**
     * <b>Estorno</b> (M7/T-M7-02, CA-9..CA-15 — SPEC-M7 §3.4 / AD-SQ-156): cria a <b>linha inversa</b>
     * do lancamento {@code id} e devolve <b>201</b> com ela. A original <b>nao</b> aparece na resposta
     * e <b>nao</b> muda — e imutavel (D-A); quem quiser ver o par abre o extrato, que mostra as duas.
     *
     * <p><b>RBAC:</b> {@code ROLE_ADMIN}, pelo matcher {@code POST /api/v1/movimentacoes/**} do
     * {@code SecurityConfig} (§3.9/AD-SQ-158). O matcher e escopado por <b>metodo</b> de proposito: um
     * matcher so de caminho casaria tambem o {@code GET} desta mesma rota (o {@code /**} do
     * {@code PathPatternParser} casa zero segmentos) e tornaria a lista global ADMIN-only, quebrando o
     * contrato do M4. USER → 403; sem token → 401.
     *
     * <p>O autor gravado e <b>quem estorna</b> (principal autenticado, nunca o autor da linha
     * original e nunca o payload — §9), com o nome resolvido por projecao escalar, igual ao
     * {@code MovimentacaoController}.
     */
    @Operation(summary = "Estorna um lancamento (ADMIN): cria a linha inversa com motivo obrigatorio, "
            + "apontando para a corrigida → 201")
    @PostMapping("/{id}/estorno")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MovimentacaoResponse> estornar(
            @PathVariable Long id,
            @Valid @RequestBody EstornoRequest request,
            @AuthenticationPrincipal Long usuarioId,
            HttpServletRequest http) {
        String usuarioNome = usuarioId == null ? null : usuarioRepository.findNomeById(usuarioId);
        return ApiResponse.ok(
                movimentacaoService.estornar(id, request.motivo(), usuarioId, usuarioNome),
                http.getRequestURI());
    }

    // ---- Handlers locais (nao tocam o GlobalExceptionHandler do M0 — §8) ----------------------

    /** 400 VALIDATION_ERROR para {@code pagina}/{@code tamanho} fora do contrato §3.3 (CA-23). */
    @ExceptionHandler(ParametroPaginacaoInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handlePaginacaoInvalida(
            ParametroPaginacaoInvalidoException ex, HttpServletRequest http) {
        ApiError error = new ApiError(
                ErrorCode.VALIDATION_ERROR.name(), ex.getMessage(), ex.getDetails());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }

    /**
     * 404 / 409 das recusas do estorno (§3.4-d, CA-12/CA-13): o proprio
     * {@link EstornoInvalidoException} carrega o {@link ErrorCode} do contrato, entao o status vem da
     * excecao e nao de um {@code if} aqui. Nada foi escrito quando qualquer uma delas sobe.
     */
    @ExceptionHandler(EstornoInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handleEstornoInvalido(
            EstornoInvalidoException ex, HttpServletRequest http) {
        return falha(ex.getCodigo(), ex.getMessage(), List.of(), http);
    }

    /**
     * 400 VALIDATION_ERROR: o inverso deixaria o estoque negativo (CA-14) — estornar uma ENTRADA cuja
     * mercadoria ja saiu. <b>Mensagem reusada</b> da {@code EstoqueInsuficienteException} do M2, como a
     * §3.4-d manda; por isso o {@code details[0].field} diz {@code quantidade}, campo que nao existe no
     * payload do estorno. Consequencia declarada do reuso (a classe herdada nao foi tocada), sem efeito
     * pratico: a tela exibe {@code error.message}.
     */
    @ExceptionHandler(EstoqueInsuficienteException.class)
    public ResponseEntity<ApiResponse<Object>> handleEstoqueInsuficiente(
            EstoqueInsuficienteException ex, HttpServletRequest http) {
        return falha(ErrorCode.VALIDATION_ERROR, ex.getMessage(), ex.getDetails(), http);
    }

    /**
     * <b>Rede da CORRIDA de dois cliques</b> (§4 #10): traduz a violacao de integridade pelo <b>NOME do
     * constraint</b>, nunca em bloco — mesmo padrao ja aprovado em {@code CorController} (armadilha #21
     * do M6). O caminho primario continua sendo o pre-check do servico
     * ({@code existsByEstornaMovimentacaoId}); aqui so cai quando duas requisicoes cruzam entre a
     * checagem e o commit, e o indice unico parcial {@code ux_mov_estorno} da V13 barra a segunda.
     *
     * <p>Este handler <b>tem precedencia</b> sobre o {@code @RestControllerAdvice} global, que
     * traduziria a violacao num 409 <b>generico</b> ("Conflito de estado."): o usuario perderia a
     * mensagem que explica o que houve. Qualquer outro constraint continua no 409 generico — mascarar
     * um CHECK quebrado como "ja estornado" seria mentir sobre a causa.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleIntegridade(
            DataIntegrityViolationException ex, HttpServletRequest http) {
        Throwable causa = ex.getMostSpecificCause();
        String texto = causa.getMessage() == null ? "" : causa.getMessage();
        log.warn("Violacao de integridade em {}: {}", http.getRequestURI(), texto);
        if (texto.contains(UX_MOV_ESTORNO)) {
            return falha(ErrorCode.CONFLICT,
                    EstornoInvalidoException.jaEstornada().getMessage(), List.of(), http);
        }
        return falha(ErrorCode.CONFLICT, ErrorCode.CONFLICT.defaultMessage(), List.of(), http);
    }

    private ResponseEntity<ApiResponse<Object>> falha(
            ErrorCode codigo, String message, List<FieldErrorItem> details, HttpServletRequest http) {
        ApiError error = new ApiError(codigo.name(), message, details);
        return ResponseEntity.status(codigo.status())
                .body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
