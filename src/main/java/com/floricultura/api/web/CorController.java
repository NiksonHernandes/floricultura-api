package com.floricultura.api.web;

import com.floricultura.api.config.OpenApiConfig;
import com.floricultura.api.service.CorConflitoException;
import com.floricultura.api.service.CorNaoEncontradaException;
import com.floricultura.api.service.CorService;
import com.floricultura.api.service.NomeCorInvalidoException;
import com.floricultura.api.web.dto.CorRequest;
import com.floricultura.api.web.dto.CorResponse;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.ParametroPaginacaoInvalidoException;
import com.floricultura.api.web.error.DiagnosticoIntegridade;
import com.floricultura.api.web.error.ErrorCode;
import com.floricultura.api.web.response.ApiError;
import com.floricultura.api.web.response.ApiResponse;
import com.floricultura.api.web.response.FieldErrorItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD do catalogo de cores (SPEC-M6 §3.2, CA-1..CA-7/CA-39) — padrao {@code FornecedorController}:
 * envelope {@link ApiResponse} do M0, {@link PaginaResponse} 0-based e handlers locais.
 *
 * <p><b>RBAC (FC-07/D5):</b> {@code GET /cores/**} e autenticado (USER+ADMIN) pelo
 * {@code anyRequest().authenticated()} — <b>sem</b> matcher de GET, porque o catalogo alimenta os filtros
 * que o USER usa; {@code POST}/{@code PUT}/{@code DELETE} exigem ADMIN (3 matchers no
 * {@code SecurityConfig}).
 *
 * <p><b>Canonizacao:</b> o corpo chega CRU e quem normaliza e o {@code CorService} via
 * {@code Cores.canonizar} (armadilha #22) — este controller <b>nao</b> transforma texto.
 */
@RestController
@RequestMapping("/api/v1/cores")
@Tag(name = "cores", description = "Catalogo de cores do produto: leitura (USER+ADMIN) paginada/detalhe "
        + "e escrita (ADMIN) criar/atualizar/excluir; o nome e sempre devolvido no formato canonico")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class CorController {

    private static final Logger log = LoggerFactory.getLogger(CorController.class);

    /** Mensagem do 400 quando o banco recusa um nome fora do canonico (§3.2.1 — bug, nao duplicata). */
    private static final String NOME_INVALIDO = "Informe um nome de cor válido.";

    private final CorService corService;

    public CorController(CorService corService) {
        this.corService = corService;
    }

    /**
     * CA-4/CA-39.6: lista paginada (default {@code pagina=0}, {@code tamanho=20}, {@code 1..100}) ordenada
     * por {@code nome ASC}, com filtro opcional {@code nome} — o termo passa pela MESMA canonizacao da
     * escrita, entao {@code ?nome=cinza escuro} acha {@code CINZA-ESCURO}.
     */
    @Operation(summary = "Lista cores paginadas (nome ASC) com filtro opcional por nome; o termo e "
            + "canonizado antes da busca, entao 'cinza escuro' acha CINZA-ESCURO")
    @GetMapping
    public ApiResponse<PaginaResponse<CorResponse>> listar(
            @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho,
            @Parameter(description = "Termo CRU; canonizado antes do filtro. Vazio = sem filtro.")
            @RequestParam(required = false) String nome,
            HttpServletRequest http) {
        return ApiResponse.ok(
                PaginaResponse.de(corService.listar(pagina, tamanho, nome), CorResponse::de),
                http.getRequestURI());
    }

    /** CA-5: detalhe por id, com {@code produtosVinculados}; inexistente → 404 (handler local). */
    @Operation(summary = "Detalha cor por id, com a contagem de produtos vinculados (404 se inexistente)")
    @GetMapping("/{id}")
    public ApiResponse<CorResponse> detalhar(@PathVariable Long id, HttpServletRequest http) {
        return ApiResponse.ok(CorResponse.de(corService.detalhar(id)), http.getRequestURI());
    }

    /**
     * CA-1/CA-2: cria a cor (ADMIN) → 201 com o nome CANONICO gravado e o {@code hex} em maiusculas.
     * Canonico ja existente → 409; canonico invalido/curto/longo → 400 {@code field:"nome"}.
     */
    @Operation(summary = "Cria cor (ADMIN) → 201 gravando o nome canonico (409 se o canonico ja existe)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CorResponse> criar(
            @Valid @RequestBody CorRequest request, HttpServletRequest http) {
        return ApiResponse.ok(
                CorResponse.de(corService.criar(request.nome(), request.hex())), http.getRequestURI());
    }

    /**
     * CA-5: atualiza nome/hex (ADMIN) → 200. Canonico de OUTRA cor → 409; renomear para o proprio
     * canonico (ate com outra caixa) → 200; id inexistente → 404.
     */
    @Operation(summary = "Atualiza cor (ADMIN) → 200 (409 se o canonico e de outra cor; 404 se inexistente)")
    @PutMapping("/{id}")
    public ApiResponse<CorResponse> atualizar(
            @PathVariable Long id,
            @Valid @RequestBody CorRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(
                CorResponse.de(corService.atualizar(id, request.nome(), request.hex())),
                http.getRequestURI());
    }

    /** CA-6: hard delete (ADMIN) → 204 sem corpo; cor em uso → 409 com a contagem real; inexistente → 404. */
    @Operation(summary = "Exclui cor (ADMIN) → 204 (409 quando a cor esta em uso por produtos)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable Long id) {
        corService.excluir(id);
    }

    // ---- Handlers locais (tem precedencia sobre o GlobalExceptionHandler do M0 — §8) ------------

    /** 400 VALIDATION_ERROR para {@code pagina}/{@code tamanho} fora do contrato §3.2 (CA-4). */
    @ExceptionHandler(ParametroPaginacaoInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handlePaginacaoInvalida(
            ParametroPaginacaoInvalidoException ex, HttpServletRequest http) {
        return falha(ErrorCode.VALIDATION_ERROR, ex.getMessage(), ex.getDetails(), http);
    }

    /** 400 VALIDATION_ERROR {@code field:"nome"} vindo do SERVICO, sobre o canonico (CA-39.4/39.5). */
    @ExceptionHandler(NomeCorInvalidoException.class)
    public ResponseEntity<ApiResponse<Object>> handleNomeInvalido(
            NomeCorInvalidoException ex, HttpServletRequest http) {
        return falha(ErrorCode.VALIDATION_ERROR, ex.getMessage(), ex.getDetails(), http);
    }

    /** 404 NOT_FOUND para id inexistente em GET/PUT/DELETE {@code /{id}} (CA-5/CA-6). */
    @ExceptionHandler(CorNaoEncontradaException.class)
    public ResponseEntity<ApiResponse<Object>> handleNaoEncontrada(
            CorNaoEncontradaException ex, HttpServletRequest http) {
        return falha(ErrorCode.NOT_FOUND, ex.getMessage(), List.of(), http);
    }

    /** 409 CONFLICT: duplicata do canonico (CA-2/CA-5) ou cor em uso no DELETE (CA-6). */
    @ExceptionHandler(CorConflitoException.class)
    public ResponseEntity<ApiResponse<Object>> handleConflito(
            CorConflitoException ex, HttpServletRequest http) {
        return falha(ErrorCode.CONFLICT, ex.getMessage(), List.of(), http);
    }

    /**
     * <b>Rede de seguranca de CORRIDA CONCORRENTE</b> (§3.2.1 / armadilha #21): traduz a violacao de
     * integridade pelo <b>NOME do constraint</b>, nunca em bloco. O caminho primario continua sendo o
     * pre-check do servico ({@code existsByNome}/{@code contarProdutos}); aqui so cai quando duas
     * requisicoes cruzam entre a checagem e o commit — as duas defesas convivem.
     *
     * <p>Este handler <b>tem precedencia</b> sobre o {@code @RestControllerAdvice} global, que traduziria
     * TODA {@link DataIntegrityViolationException} num 409 generico — e {@code ck_cor_nome_canonico} em
     * 409 mascararia um bug do normalizador como duplicata. Nenhum caminho devolve 500.
     *
     * <ul>
     *   <li>{@code uk_cor_nome} → 409 "Ja existe uma cor com esse nome."</li>
     *   <li>{@code ck_cor_nome_canonico} → 400 {@code field:"nome"} (bug do normalizador/UPDATE manual)</li>
     *   <li>{@code fk_pc_cor} ({@code ON DELETE RESTRICT}) → 409 com a contagem recontada</li>
     * </ul>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleIntegridade(
            DataIntegrityViolationException ex, HttpServletRequest http) {
        Throwable causa = ex.getMostSpecificCause();
        String texto = causa.getMessage() == null ? "" : causa.getMessage();
        // LGPD (AD-SQ-170): `texto` decide a rota em memoria, mas NAO vai ao log — a mensagem do
        // PostgreSQL anexa `Detail: Failing row contains (…)`. Ao log vai so o metadado de esquema.
        log.warn("Violacao de integridade em {}: {}",
                http.getRequestURI(), DiagnosticoIntegridade.de(ex));
        if (texto.contains("ck_cor_nome_canonico")) {
            return falha(ErrorCode.VALIDATION_ERROR, NOME_INVALIDO,
                    List.of(new FieldErrorItem("nome", NOME_INVALIDO)), http);
        }
        if (texto.contains("fk_pc_cor")) {
            long uso = usoDaCorDaUri(http);
            return falha(ErrorCode.CONFLICT, uso > 0
                            ? CorConflitoException.emUso(uso).getMessage()
                            : "Cor em uso por produtos — desvincule dos produtos antes de excluir.",
                    List.of(), http);
        }
        // uk_cor_nome e qualquer outra violacao sob /cores: 409, como o advice global ja faria.
        return falha(ErrorCode.CONFLICT, texto.contains("uk_cor_nome")
                ? CorConflitoException.duplicada().getMessage()
                : ErrorCode.CONFLICT.defaultMessage(), List.of(), http);
    }

    /**
     * Contagem REAL de vinculos da cor do {@code /cores/{id}} que estourou o {@code RESTRICT} — o handler
     * nao recebe o {@code @PathVariable}, entao le o id do fim da URI. {@code 0} quando nao da para saber
     * (id ilegivel ou cor removida na corrida): ai a mensagem sai sem numero, em vez de inventar um.
     *
     * <p><b>DECISAO (nao "consertar"):</b> preferimos a mensagem SEM o numero a estimar um {@code N}
     * qualquer. E caminho de corrida raro — ja coberto pelo pre-check do servico — e dado honesto vale
     * mais que mensagem completa. Trocar o {@code 0} por um valor inventado seria uma regressao.
     */
    private long usoDaCorDaUri(HttpServletRequest http) {
        String uri = http.getRequestURI();
        try {
            return corService.detalhar(Long.parseLong(uri.substring(uri.lastIndexOf('/') + 1)))
                    .produtosVinculados();
        } catch (RuntimeException naoDeuParaRecontar) {
            return 0L;
        }
    }

    private ResponseEntity<ApiResponse<Object>> falha(
            ErrorCode code, String message, List<FieldErrorItem> details, HttpServletRequest http) {
        ApiError error = new ApiError(code.name(), message, details);
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(error, http.getRequestURI()));
    }
}
