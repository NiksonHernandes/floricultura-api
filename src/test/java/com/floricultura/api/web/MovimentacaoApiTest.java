package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.EstoqueInsuficienteException;
import com.floricultura.api.service.JwtService;
import com.floricultura.api.service.MovimentacaoService;
import com.floricultura.api.web.dto.MovimentacaoRequest;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integracao da movimentacao de estoque do M2 (T-M2-4, CA-10/CA-11/CA-12/CA-14) contra um PostgreSQL
 * de DESCARTE (Testcontainers postgres:16), com {@code MovimentacaoController}/{@code
 * MovimentacaoService}, {@code SecurityConfig} endurecido, filtro JWT, Flyway V1..V4 e a trigger de
 * imutabilidade reais no contexto. Prova: efeitos ENTRADA/SAIDA/AJUSTE + snapshot no ledger
 * ({@code produto_nome}/{@code usuario_id}), o bloqueio de SAIDA com a mensagem exata <b>sem</b> gravar
 * nada, o RBAC (ADMIN 201 / USER 403 — pelo matcher {@code POST /produtos/**}), o historico paginado
 * {@code criadoEm DESC} e — sobretudo — a <b>atomicidade sob concorrencia</b> (duas saidas simultaneas
 * sob lock pessimista: exatamente uma sucede, estoque nunca negativo).
 *
 * <p>Nome {@code *Test} (Surefire): o repo <b>nao</b> configura Failsafe — integracoes Testcontainers
 * usam {@code *Test} para rodarem no {@code clean verify} (mesma nota do {@code ProdutoApiTest}).
 * <b>Sem segredo versionado (§9):</b> os {@code senha_hash} de seed sao BCrypt de {@link UUID} de
 * runtime.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MovimentacaoApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-movimentacao-0123456789ab");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MovimentacaoService movimentacaoService;

    private Long adminId;
    private String adminBearer;
    private String userBearer;

    @BeforeEach
    void seed() {
        // TRUNCATE (nao DELETE): o ledger e imutavel (AD-SQ-8) e a trigger BEFORE DELETE row-level
        // rejeita qualquer DELETE de linha ja gravada; TRUNCATE (statement-level) nao a dispara e
        // limpa o container descartavel entre os testes. As demais suites usam DELETE porque nunca
        // inserem movimentacoes (tabela vazia = nenhuma linha para a trigger barrar).
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        adminId = inserirUsuario("admin-mov@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("user-mov@floricultura.local", "USER");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    /** Insere um produto com estoque_atual definido (estoque_minimo=1, un); devolve o id gerado. */
    private Long inserirProduto(String nome, String estoqueAtual) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES (?, 'un', 1, ?) RETURNING id",
                Long.class, nome, new BigDecimal(estoqueAtual));
    }

    private BigDecimal estoqueAtualDe(Long produtoId) {
        return jdbc.queryForObject(
                "SELECT estoque_atual FROM produto WHERE id = ?", BigDecimal.class, produtoId);
    }

    private long contarMovimentacoes(Long produtoId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM movimentacao_estoque WHERE produto_id = ?",
                Long.class, produtoId);
    }

    private void movimentarViaApi(Long produtoId, String bearer, String tipo, String quantidade)
            throws Exception {
        String body = "{\"tipo\":\"" + tipo + "\",\"quantidade\":" + quantidade + "}";
        mockMvc.perform(post("/api/v1/produtos/" + produtoId + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    // ---- CA-10: ENTRADA (ADMIN) 201 + ledger com snapshot/usuario / USER 403 / sem token 401 --

    @Test
    void entrada_comAdmin_incrementaEstoqueEGravaLedger() throws Exception {
        Long id = inserirProduto("Rosa Vermelha", "0");
        String body = "{\"tipo\":\"ENTRADA\",\"quantidade\":30,\"motivo\":\"Compra\"}";

        mockMvc.perform(post("/api/v1/produtos/" + id + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.produtoId").value(id))
                .andExpect(jsonPath("$.data.produtoNome").value("Rosa Vermelha"))
                .andExpect(jsonPath("$.data.tipo").value("ENTRADA"))
                .andExpect(jsonPath("$.data.quantidadeResultante").value(30.0))
                .andExpect(jsonPath("$.data.usuarioId").value(adminId))
                .andExpect(jsonPath("$.data.criadoEm").isNotEmpty());

        // Produto foi a estoqueAtual=30 (snapshot).
        assertEquals(0, estoqueAtualDe(id).compareTo(new BigDecimal("30")));
        // Ledger tem a linha com produto_nome snapshot + usuario_id do autenticado.
        Long usuarioLedger = jdbc.queryForObject(
                "SELECT usuario_id FROM movimentacao_estoque WHERE produto_id = ?", Long.class, id);
        String nomeLedger = jdbc.queryForObject(
                "SELECT produto_nome FROM movimentacao_estoque WHERE produto_id = ?",
                String.class, id);
        assertEquals(adminId, usuarioLedger);
        assertEquals("Rosa Vermelha", nomeLedger);
    }

    @Test
    void movimentar_comUser_devolve403() throws Exception {
        Long id = inserirProduto("Rosa", "0");
        String body = "{\"tipo\":\"ENTRADA\",\"quantidade\":30}";

        mockMvc.perform(post("/api/v1/produtos/" + id + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, userBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        // USER bloqueado no SecurityConfig: nada gravado.
        assertEquals(0, contarMovimentacoes(id));
    }

    @Test
    void movimentar_semToken_devolve401() throws Exception {
        Long id = inserirProduto("Rosa", "0");

        mockMvc.perform(post("/api/v1/produtos/" + id + "/movimentacoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"ENTRADA\",\"quantidade\":30}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void movimentar_produtoInexistente_devolve404() throws Exception {
        mockMvc.perform(post("/api/v1/produtos/999999/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"ENTRADA\",\"quantidade\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- CA-11: SAIDA dentro do estoque / bloqueio com mensagem exata sem gravar --------------

    @Test
    void saida_dentroDoEstoque_decrementa() throws Exception {
        Long id = inserirProduto("Rosa", "30");

        mockMvc.perform(post("/api/v1/produtos/" + id + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"SAIDA\",\"quantidade\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tipo").value("SAIDA"))
                .andExpect(jsonPath("$.data.quantidadeResultante").value(20.0));

        assertEquals(0, estoqueAtualDe(id).compareTo(new BigDecimal("20")));
    }

    @Test
    void saida_acimaDoEstoque_devolve400ComMensagemENaoGrava() throws Exception {
        Long id = inserirProduto("Rosa", "20");

        mockMvc.perform(post("/api/v1/produtos/" + id + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"SAIDA\",\"quantidade\":999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Estoque insuficiente (20 em estoque)."))
                .andExpect(jsonPath("$.error.details[0].field").value("quantidade"));

        // CA-11: estoque intacto (20) e ledger vazio (nada gravado).
        assertEquals(0, estoqueAtualDe(id).compareTo(new BigDecimal("20")));
        assertEquals(0, contarMovimentacoes(id));
    }

    // ---- CA-12: AJUSTE define alvo (0 = zerar, sem deletar) / ENTRADA 0 -> 400 ----------------

    @Test
    void ajuste_defineQuantidadeAlvo() throws Exception {
        Long id = inserirProduto("Rosa", "20");

        mockMvc.perform(post("/api/v1/produtos/" + id + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"AJUSTE\",\"quantidade\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.quantidadeResultante").value(5.0));

        assertEquals(0, estoqueAtualDe(id).compareTo(new BigDecimal("5")));
    }

    @Test
    void ajuste_zero_zeraEstoqueSemDeletarProduto() throws Exception {
        Long id = inserirProduto("Rosa", "20");

        mockMvc.perform(post("/api/v1/produtos/" + id + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"AJUSTE\",\"quantidade\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.quantidadeResultante").value(0.0));

        assertEquals(0, estoqueAtualDe(id).compareTo(BigDecimal.ZERO));
        // Produto NAO foi deletado (zerar e ajuste, nao delete — FC-08).
        Long existe = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE id = ?", Long.class, id);
        assertEquals(1L, existe);
    }

    @Test
    void entrada_zero_devolve400() throws Exception {
        Long id = inserirProduto("Rosa", "10");

        mockMvc.perform(post("/api/v1/produtos/" + id + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"ENTRADA\",\"quantidade\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("quantidade"));

        assertEquals(0, contarMovimentacoes(id));
    }

    // ---- CA-14: historico paginado (USER+ADMIN) ordenado criadoEm DESC / 404 -----------------

    @Test
    void historico_comUser_devolve200PaginadoOrdenadoDesc() throws Exception {
        Long id = inserirProduto("Rosa", "0");
        movimentarViaApi(id, adminBearer, "ENTRADA", "5");
        movimentarViaApi(id, adminBearer, "ENTRADA", "5");
        movimentarViaApi(id, adminBearer, "SAIDA", "3"); // a mais recente

        mockMvc.perform(get("/api/v1/produtos/" + id + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo").isArray())
                .andExpect(jsonPath("$.data.totalElementos").value(3))
                .andExpect(jsonPath("$.data.pagina").value(0))
                .andExpect(jsonPath("$.data.tamanho").value(20))
                // criadoEm DESC → a SAIDA (ultima registrada) vem primeiro.
                .andExpect(jsonPath("$.data.conteudo[0].tipo").value("SAIDA"))
                .andExpect(jsonPath("$.data.conteudo[0].quantidadeResultante").value(7.0));
    }

    @Test
    void historico_comAdmin_tambemDevolve200() throws Exception {
        Long id = inserirProduto("Rosa", "0");
        movimentarViaApi(id, adminBearer, "ENTRADA", "5");

        mockMvc.perform(get("/api/v1/produtos/" + id + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1));
    }

    @Test
    void historico_produtoInexistente_devolve404() throws Exception {
        mockMvc.perform(get("/api/v1/produtos/999999/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void historico_paginacaoInvalida_devolve400() throws Exception {
        Long id = inserirProduto("Rosa", "0");

        mockMvc.perform(get("/api/v1/produtos/" + id + "/movimentacoes?tamanho=0")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("tamanho"));
    }

    // ---- Concorrencia: duas SAIDAS simultaneas sob lock pessimista (AD-SQ-30, §4/§12) ---------

    @Test
    void duasSaidasConcorrentes_apenasUmaSucede_estoqueNuncaNegativo() throws Exception {
        Long id = inserirProduto("Rosa Concorrente", "10");
        MovimentacaoRequest saida = new MovimentacaoRequest("SAIDA", new BigDecimal("10"), "corrida");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        Callable<Boolean> tarefa = () -> {
            largada.await();
            try {
                movimentacaoService.movimentar(id, saida, adminId);
                return Boolean.TRUE;  // saida aplicada
            } catch (EstoqueInsuficienteException e) {
                return Boolean.FALSE; // bloqueada pelo lock+checagem
            }
        };

        Future<Boolean> f1 = pool.submit(tarefa);
        Future<Boolean> f2 = pool.submit(tarefa);
        largada.countDown(); // dispara as duas ao mesmo tempo
        boolean r1 = f1.get(15, TimeUnit.SECONDS);
        boolean r2 = f2.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        // Exatamente UMA sucede: o lock pessimista serializa a checagem+escrita (sem furar o estoque).
        assertEquals(1, (r1 ? 1 : 0) + (r2 ? 1 : 0));
        // Estoque final = 0, NUNCA negativo.
        assertEquals(0, estoqueAtualDe(id).compareTo(BigDecimal.ZERO));
        // Apenas 1 linha no ledger (a bloqueada nao grava — atomicidade).
        assertEquals(1, contarMovimentacoes(id));
    }
}
