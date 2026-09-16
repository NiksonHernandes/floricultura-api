package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.EstornoInvalidoException;
import com.floricultura.api.service.JwtService;
import com.floricultura.api.service.MovimentacaoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.floricultura.api.web.response.ApiResponse;

/**
 * Integracao do <b>estorno</b> do M7 (T-M7-02, CA-9..CA-15 — SPEC-M7 §3.4 / AD-SQ-156) contra um
 * PostgreSQL de DESCARTE (Testcontainers postgres:16), com controller/servico/SecurityConfig/JWT/
 * Flyway V1..V13 reais. Dados ficticios (LGPD): nomes de planta e de sitio.
 *
 * <p><b>Dois ADMINs distintos, de proposito:</b> um registra a movimentacao original e outro a
 * estorna. Com um unico admin, a assercao "o autor gravado e quem estornou" (§3.4-a) ficaria
 * <b>verde mesmo se o codigo copiasse o autor da linha original</b> — o CA-9 nao teria dente.
 *
 * <p><b>As tres camadas da corrida</b> (§4 #10) sao provadas <b>separadamente</b>, porque elas falham
 * por motivos diferentes: o pre-check do servico ({@link #segundoEstornoDaMesmaLinhaDevolve409}), o
 * indice unico parcial no banco ({@link #indiceUnicoParcialRecusaSegundoPonteiro}) e a traducao da
 * violacao para a mensagem do contrato ({@link #violacaoDeUxMovEstornoTraduzidaPara409DoContrato}).
 * O caso concorrente de verdade ({@link #doisEstornosConcorrentesApenasUmSucede}) assere a
 * <b>disjuncao</b> das duas ultimas: qual delas pega depende do escalonador, e fingir determinismo
 * ali produziria teste intermitente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MovimentacaoEstornoTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String MOTIVO = "{\"motivo\":\"Valor digitado errado (2,50 em vez de 0,25).\"}";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-estorno-api-0123456789abcd");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MovimentacaoService movimentacaoService;

    @Autowired
    private MovimentacaoConsultaController controller;

    private Long autorId;
    private Long estornadorId;
    private String autorBearer;
    private String estornadorBearer;
    private String userBearer;
    private Long produtoId;
    private Long clienteId;

    @BeforeEach
    void seed() {
        // TRUNCATE: o ledger e imutavel (a trigger barra DELETE de linha).
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM cliente");
        jdbc.update("DELETE FROM usuario");
        autorId = inserirUsuario("Admin Autor", "autor-estorno@floricultura.local", "ADMIN");
        estornadorId =
                inserirUsuario("Admin Estornador", "estornador@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("Operador", "user-estorno@floricultura.local", "USER");
        autorBearer = "Bearer " + jwtService.gerarToken(autorId);
        estornadorBearer = "Bearer " + jwtService.gerarToken(estornadorId);
        userBearer = "Bearer " + jwtService.gerarToken(userId);
        produtoId = inserirProduto("Rosa Vermelha", "100");
        clienteId = jdbc.queryForObject(
                "INSERT INTO cliente (nome) VALUES ('Sitio Boa Flor') RETURNING id", Long.class);
    }

    private Long inserirUsuario(String nome, String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, nome, email, ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    private Long inserirProduto(String nome, String estoqueAtual) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES (?, 'un', 1, ?) RETURNING id",
                Long.class, nome, new BigDecimal(estoqueAtual));
    }

    /** Registra um lancamento pelo ADMIN AUTOR e devolve o id da linha criada. */
    private Long registrar(Long produto, String corpo) throws Exception {
        String json = mockMvc.perform(post("/api/v1/produtos/" + produto + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, autorBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = JSON.readTree(json).path("data");
        return data.path("id").asLong();
    }

    /** A SAIDA do CA-9: 12 un a 2,50 com 15 % ⇒ bruto 30,00 e final 25,50, com cliente. */
    private Long registrarSaidaComValores() throws Exception {
        return registrar(produtoId, "{\"tipo\":\"SAIDA\",\"quantidade\":12,\"motivo\":\"Venda balcao\","
                + "\"clienteId\":" + clienteId + ",\"valorUnitario\":2.50,"
                + "\"descontoTipo\":\"PERCENTUAL\",\"descontoValor\":15}");
    }

    private ResultActions estornar(Long id, String bearer, String corpo) throws Exception {
        return mockMvc.perform(post("/api/v1/movimentacoes/" + id + "/estorno")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private BigDecimal estoqueAtualDe(Long produto) {
        return jdbc.queryForObject(
                "SELECT estoque_atual FROM produto WHERE id = ?", BigDecimal.class, produto);
    }

    private long contarLancamentos() {
        return jdbc.queryForObject("SELECT count(*) FROM movimentacao_estoque", Long.class);
    }

    /** Soma com sinal (ENTRADA = +1, SAIDA = -1) de uma coluna, sobre o PAR original+estorno. */
    private BigDecimal somaComSinal(String coluna, Long original, Long estorno) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE tipo WHEN 'ENTRADA' THEN " + coluna
                        + " ELSE -" + coluna + " END), 0) FROM movimentacao_estoque "
                        + "WHERE id IN (?, ?)",
                BigDecimal.class, original, estorno);
    }

    // ---- CA-9: a linha nova, invertida, com ponteiro e autor do ESTORNO ----------------------

    @Test
    void adminEstornaSaidaCriaEntradaInvertida() throws Exception {
        Long original = registrarSaidaComValores();

        estornar(original, estornadorBearer, MOTIVO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tipo").value("ENTRADA"))
                .andExpect(jsonPath("$.data.quantidade").value(12))
                .andExpect(jsonPath("$.data.produtoId").value(produtoId))
                .andExpect(jsonPath("$.data.produtoNome").value("Rosa Vermelha"))
                // Valores COPIADOS da original, nao recalculados (§3.4-a).
                .andExpect(jsonPath("$.data.valorUnitario").value(2.50))
                .andExpect(jsonPath("$.data.descontoTipo").value("PERCENTUAL"))
                .andExpect(jsonPath("$.data.descontoValor").value(15.00))
                .andExpect(jsonPath("$.data.totalBruto").value(30.00))
                .andExpect(jsonPath("$.data.totalFinal").value(25.50))
                // O ponteiro e o que liga o par — a rastreabilidade e a coluna, nao o texto.
                .andExpect(jsonPath("$.data.estornaMovimentacaoId").value(original))
                // Autor = quem ESTORNOU (por isso a suite tem dois ADMINs distintos).
                .andExpect(jsonPath("$.data.usuarioId").value(estornadorId))
                .andExpect(jsonPath("$.data.usuarioNome").value("Admin Estornador"))
                // Contraparte NAO e copiada (§3.4-b): o tipo inverteu e os CHECKs da V10 mandam.
                .andExpect(jsonPath("$.data.clienteId").doesNotExist())
                .andExpect(jsonPath("$.data.clienteNome").doesNotExist())
                .andExpect(jsonPath("$.data.fornecedorId").doesNotExist())
                .andExpect(jsonPath("$.data.motivo")
                        .value("Valor digitado errado (2,50 em vez de 0,25)."));

        // A ORIGINAL nao mudou: continua SAIDA, com a contraparte dela e sem ponteiro.
        assertEquals("SAIDA", jdbc.queryForObject(
                "SELECT tipo FROM movimentacao_estoque WHERE id = ?", String.class, original));
        assertEquals(clienteId, jdbc.queryForObject(
                "SELECT cliente_id FROM movimentacao_estoque WHERE id = ?", Long.class, original));
    }

    // ---- CA-10: o estoque volta e a conta do par fecha em ZERO ------------------------------

    @Test
    void estornoDevolveEstoqueESomaComSinalZera() throws Exception {
        BigDecimal estoqueAntesDaOriginal = estoqueAtualDe(produtoId);
        Long original = registrarSaidaComValores();
        assertEquals(0, new BigDecimal("88").compareTo(estoqueAtualDe(produtoId)),
                "a original saiu do estoque antes do estorno");

        String json = estornar(original, estornadorBearer, MOTIVO)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long estorno = JSON.readTree(json).path("data").path("id").asLong();

        assertEquals(0, estoqueAntesDaOriginal.compareTo(estoqueAtualDe(produtoId)),
                "estoque_atual volta ao valor anterior a original");
        assertEquals(0, BigDecimal.ZERO.compareTo(somaComSinal("quantidade", original, estorno)),
                "SUM(quantidade x sinal) do par = 0");
        assertEquals(0, BigDecimal.ZERO.compareTo(somaComSinal("total_final", original, estorno)),
                "SUM(total_final x sinal) do par = 0");
    }

    // ---- CA-11: motivo obrigatorio (PA#2) ---------------------------------------------------

    @Test
    void motivoAusenteEmBrancoOuCurtoDevolve400() throws Exception {
        Long original = registrarSaidaComValores();
        long lancamentosAntes = contarLancamentos();
        BigDecimal estoqueAntes = estoqueAtualDe(produtoId);

        for (String corpo : new String[] {"{}", "{\"motivo\":\"   \"}", "{\"motivo\":\"ab\"}"}) {
            estornar(original, estornadorBearer, corpo)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("motivo"));
        }

        assertEquals(lancamentosAntes, contarLancamentos(), "nada foi gravado no ledger");
        assertEquals(0, estoqueAntes.compareTo(estoqueAtualDe(produtoId)), "estoque inalterado");
    }

    // ---- CA-12 / CA-13: os quatro 409, cada um com a mensagem do §3.4-d ---------------------

    @Test
    void segundoEstornoDaMesmaLinhaDevolve409() throws Exception {
        Long original = registrarSaidaComValores();
        estornar(original, estornadorBearer, MOTIVO).andExpect(status().isCreated());
        long lancamentosAntes = contarLancamentos();

        estornar(original, estornadorBearer, MOTIVO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("Este lançamento já foi estornado."));

        assertEquals(lancamentosAntes, contarLancamentos(), "a recusa nao grava linha");
    }

    @Test
    void estornoDeEstornoDevolve409() throws Exception {
        Long original = registrarSaidaComValores();
        String json = estornar(original, estornadorBearer, MOTIVO)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long estorno = JSON.readTree(json).path("data").path("id").asLong();

        estornar(estorno, estornadorBearer, MOTIVO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("Um estorno não pode ser estornado."));
    }

    @Test
    void estornoDeAjusteDevolve409() throws Exception {
        Long ajuste = registrar(produtoId,
                "{\"tipo\":\"AJUSTE\",\"quantidade\":40,\"motivo\":\"Recontagem\"}");

        estornar(ajuste, estornadorBearer, MOTIVO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message")
                        .value("Lançamento de AJUSTE não é estornável; registre um novo AJUSTE."));
    }

    @Test
    void estornoDeLinhaComProdutoExcluidoDevolve409() throws Exception {
        Long descartavel = inserirProduto("Lirio Branco", "50");
        Long orfa = registrar(descartavel, "{\"tipo\":\"SAIDA\",\"quantidade\":5,\"motivo\":\"Venda\"}");
        // Hard delete do produto (FC-08): a FK ON DELETE SET NULL anula o vinculo e a linha fica orfa.
        jdbc.update("DELETE FROM produto WHERE id = ?", descartavel);

        estornar(orfa, estornadorBearer, MOTIVO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message")
                        .value("Produto excluído: não é possível estornar este lançamento."));
    }

    @Test
    void movimentacaoInexistenteDevolve404() throws Exception {
        estornar(999_999L, estornadorBearer, MOTIVO)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Movimentação não encontrada."));
    }

    // ---- CA-14: o inverso deixaria o estoque negativo ---------------------------------------

    @Test
    void estornoDeEntradaSemEstoqueDevolve400() throws Exception {
        Long produto = inserirProduto("Girassol", "0");
        Long entrada = registrar(produto, "{\"tipo\":\"ENTRADA\",\"quantidade\":10,\"motivo\":\"Compra\"}");
        // A mercadoria ja saiu: nao ha o que devolver ao fornecedor (§4 #6).
        registrar(produto, "{\"tipo\":\"SAIDA\",\"quantidade\":10,\"motivo\":\"Venda\"}");
        long lancamentosAntes = contarLancamentos();

        estornar(entrada, estornadorBearer, MOTIVO)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Estoque insuficiente (0 em estoque)."));

        assertEquals(lancamentosAntes, contarLancamentos(), "nada gravado");
        assertEquals(0, BigDecimal.ZERO.compareTo(estoqueAtualDe(produto)), "estoque intacto");
    }

    // ---- CA-15: RBAC do primeiro POST de /movimentacoes ------------------------------------

    /**
     * O fixture usa uma SAIDA <b>fresca e ainda nao estornada</b> de proposito: e o que torna o
     * {@code 201} alcancavel quando o matcher {@code POST /api/v1/movimentacoes/**} e removido
     * (mutacao §10 #8b). Com uma linha ja estornada, a mutacao devolveria {@code 409} e o
     * {@code expected:<403> but was:<201>} que a rubrica nomeia seria <b>inatingivel</b>.
     */
    @Test
    void userAutenticadoNaoEstorna() throws Exception {
        Long original = registrarSaidaComValores();
        long lancamentosAntes = contarLancamentos();

        estornar(original, userBearer, MOTIVO).andExpect(status().isForbidden());

        assertEquals(lancamentosAntes, contarLancamentos(), "USER nao grava linha de estorno");
    }

    @Test
    void semTokenNaoEstorna() throws Exception {
        Long original = registrarSaidaComValores();

        mockMvc.perform(post("/api/v1/movimentacoes/" + original + "/estorno")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MOTIVO))
                .andExpect(status().isUnauthorized());
    }

    // ---- A corrida, em tres provas deterministas + uma concorrente honesta ------------------

    /**
     * Camada 2: o indice unico <b>parcial</b> {@code ux_mov_estorno} da V13 recusa um segundo ponteiro
     * para a mesma linha — <b>no banco</b>, sem passar pelo servico. INSERT direto e legitimo (o ledger
     * e insert-only; a trigger so barra UPDATE/DELETE).
     */
    @Test
    void indiceUnicoParcialRecusaSegundoPonteiro() throws Exception {
        Long original = registrarSaidaComValores();
        estornar(original, estornadorBearer, MOTIVO).andExpect(status().isCreated());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO movimentacao_estoque (produto_id, produto_nome, tipo, quantidade, "
                        + "quantidade_resultante, motivo, usuario_id, estorna_movimentacao_id) "
                        + "VALUES (?, 'Rosa Vermelha', 'ENTRADA', 12, 112, 'segundo estorno', ?, ?)",
                produtoId, estornadorId, original));
    }

    /**
     * Camada 3: a violacao do indice vira <b>409 com a mensagem do contrato</b>, e nao o 409 generico
     * do handler global — sem isso o usuario receberia "Conflito de estado." e nao saberia o que houve.
     * Chamado direto no handler (mesmo padrao do {@code CorApiTest}), porque provocar a corrida real
     * com threads para <b>esta</b> assercao seria teste intermitente.
     */
    @Test
    void violacaoDeUxMovEstornoTraduzidaPara409DoContrato() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRequestURI("/api/v1/movimentacoes/1/estorno");

        ResponseEntity<ApiResponse<Object>> resposta = controller.handleIntegridade(
                new DataIntegrityViolationException("could not execute statement",
                        new RuntimeException("ERROR: duplicate key value violates unique "
                                + "constraint \"ux_mov_estorno\"")),
                http);

        assertEquals(409, resposta.getStatusCode().value());
        assertEquals("Este lançamento já foi estornado.",
                resposta.getBody().error().message());
        assertEquals("CONFLICT", resposta.getBody().error().code());
    }

    /**
     * A corrida de verdade: dois estornos simultaneos da <b>mesma</b> linha. Exatamente um sucede e o
     * outro e recusado — pelo pre-check do servico <b>ou</b> pelo indice unico, conforme o escalonador
     * (os dois desembocam no mesmo 409 na API). Afirmar qual deles pega seria inventar determinismo.
     */
    @Test
    void doisEstornosConcorrentesApenasUmSucede() throws Exception {
        Long original = registrarSaidaComValores();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        Callable<RuntimeException> tarefa = () -> {
            largada.await();
            try {
                movimentacaoService.estornar(original, "corrida de dois cliques", estornadorId,
                        "Admin Estornador");
                return null; // sucesso
            } catch (RuntimeException e) {
                return e;
            }
        };

        Future<RuntimeException> f1 = pool.submit(tarefa);
        Future<RuntimeException> f2 = pool.submit(tarefa);
        largada.countDown();
        RuntimeException r1 = f1.get(30, TimeUnit.SECONDS);
        RuntimeException r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, (r1 == null ? 1 : 0) + (r2 == null ? 1 : 0), "exatamente um estorno sucede");
        RuntimeException recusa = r1 == null ? r2 : r1;
        if (!(recusa instanceof EstornoInvalidoException)) {
            assertInstanceOf(DataIntegrityViolationException.class, recusa,
                    "a recusa vem do pre-check ou do indice unico — nunca de outro erro");
        }
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT count(*) FROM movimentacao_estoque WHERE estorna_movimentacao_id = ?",
                Long.class, original), "uma unica linha de estorno no banco");
    }
}
