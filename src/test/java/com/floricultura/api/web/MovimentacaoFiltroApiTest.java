package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Filtros do {@code GET /api/v1/movimentacoes} (T-M7-03, SPEC-M7 §3.5 — CA-16..CA-19) contra um
 * PostgreSQL de DESCARTE (Testcontainers postgres:16).
 *
 * <p>As linhas do ledger sao semeadas por {@code INSERT} direto, e nao pelo {@code POST}, por uma
 * razao de prova: {@code criado_em} tem {@code DEFAULT now()} e e {@code insertable=false} na entidade,
 * entao pela API <b>nao ha como cravar o horario</b> — e o horario e justamente o que a CA-17
 * verifica. O {@code INSERT} direto permite o lancamento das <b>23:30 do dia final</b> em
 * {@code America/Sao_Paulo}, que e o caso que separa "dia final inteiro" de "dia final ate 00:00".
 *
 * <p>Toda assercao de filtro confere {@code totalElementos} (o numero da <b>countQuery</b>) junto com
 * o conteudo: e o par que denuncia uma {@code countQuery} que divergiu da query de dados.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MovimentacaoFiltroApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static final String BASE = "/api/v1/movimentacoes";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-movfiltro-0123456789ab");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String bearer;
    private Long rosa;
    private Long lirio;
    private Long fornecedor;
    private Long cliente;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM fornecedor");
        jdbc.update("DELETE FROM cliente");
        jdbc.update("DELETE FROM usuario");
        Long userId = inserirUsuario("Beto", "user-mf@floricultura.local");
        bearer = "Bearer " + jwtService.gerarToken(userId);
        rosa = inserirProduto("Rosa Vermelha");
        lirio = inserirProduto("Lirio Branco");
        fornecedor = jdbc.queryForObject(
                "INSERT INTO fornecedor (nome) VALUES ('Sitio Boa Flor') RETURNING id", Long.class);
        cliente = jdbc.queryForObject(
                "INSERT INTO cliente (nome) VALUES ('Maria das Flores') RETURNING id", Long.class);
    }

    private Long inserirUsuario(String nome, String email) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, 'USER', true, false) RETURNING id",
                Long.class, nome, email, ENCODER.encode(UUID.randomUUID().toString()));
    }

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES (?, 'un', 1, 0) RETURNING id",
                Long.class, nome);
    }

    /**
     * Grava uma linha do ledger com {@code criado_em} cravado. {@code instanteLocal} e lido como hora
     * de {@code America/Sao_Paulo} e convertido para {@code TIMESTAMPTZ} pelo proprio banco — a mesma
     * fronteira de fuso que o filtro atravessa.
     */
    private Long lancamento(Long produtoId, String tipo, String instanteLocal) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque "
                        + "(produto_id, produto_nome, tipo, quantidade, quantidade_resultante, "
                        + " usuario_nome, criado_em) "
                        + "VALUES (?, (SELECT nome FROM produto WHERE id = ?), ?, 1, 1, 'Beto', "
                        + " CAST(? AS timestamp) AT TIME ZONE 'America/Sao_Paulo') RETURNING id",
                Long.class, produtoId, produtoId, tipo, instanteLocal);
    }

    /**
     * Idem, ja com a contraparte da V10 gravada no proprio INSERT — o ledger e imutavel, nao da para
     * "completar depois" com UPDATE. {@code coluna} e {@code fornecedor} ou {@code cliente}; os CHECKs
     * {@code ck_mov_*_tipo} exigem fornecedor em ENTRADA e cliente em SAIDA.
     */
    private Long lancamentoComContraparte(
            Long produtoId, String tipo, String instanteLocal, String coluna, Long id, String nome) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque "
                        + "(produto_id, produto_nome, tipo, quantidade, quantidade_resultante, "
                        + " usuario_nome, criado_em, " + coluna + "_id, " + coluna + "_nome) "
                        + "VALUES (?, (SELECT nome FROM produto WHERE id = ?), ?, 1, 1, 'Beto', "
                        + " CAST(? AS timestamp) AT TIME ZONE 'America/Sao_Paulo', ?, ?) RETURNING id",
                Long.class, produtoId, produtoId, tipo, instanteLocal, id, nome);
    }

    // ---- CA-16: sem parametros novos, a resposta e a de hoje --------------------------------------

    @Test
    void semParametrosNovos_respostaIdenticaADeHoje() throws Exception {
        lancamento(rosa, "ENTRADA", "2026-03-10 09:00:00");
        lancamento(lirio, "SAIDA", "2026-03-11 09:00:00"); // mais recente

        // Sem nenhum parametro: tudo, ordem criado_em DESC (o mais recente primeiro).
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(2))
                .andExpect(jsonPath("$.data.conteudo[0].produtoNome").value("Lirio Branco"))
                .andExpect(jsonPath("$.data.conteudo[1].produtoNome").value("Rosa Vermelha"));

        // O q herdado (produto_nome OU usuario_nome) continua valendo sozinho.
        mockMvc.perform(get(BASE + "?q=rosa").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].produtoNome").value("Rosa Vermelha"));

        mockMvc.perform(get(BASE + "?q=beto").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(2));

        // Paginacao herdada intacta.
        mockMvc.perform(get(BASE + "?pagina=0&tamanho=1").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(2))
                .andExpect(jsonPath("$.data.conteudo.length()").value(1));
    }

    // ---- CA-17: de/ate em America/Sao_Paulo, com o DIA FINAL INTEIRO -----------------------------

    @Test
    void intervaloDeAte_incluiODiaFinalInteiro() throws Exception {
        lancamento(rosa, "ENTRADA", "2026-03-09 23:30:00"); // vespera de `de` — FORA
        lancamento(rosa, "ENTRADA", "2026-03-10 00:15:00"); // 1o instante de `de` — DENTRO
        lancamento(rosa, "SAIDA", "2026-03-12 23:30:00"); // 23:30 do dia `ate` — DENTRO (o caso)
        lancamento(rosa, "SAIDA", "2026-03-13 00:10:00"); // dia seguinte a `ate` — FORA

        // As 4 linhas existem; o recorte e que separa.
        assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM movimentacao_estoque", Integer.class));

        mockMvc.perform(get(BASE + "?de=2026-03-10&ate=2026-03-12")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(2))
                .andExpect(jsonPath("$.data.conteudo.length()").value(2))
                // ordem DESC: o das 23:30 do dia final vem primeiro
                .andExpect(jsonPath("$.data.conteudo[0].tipo").value("SAIDA"))
                .andExpect(jsonPath("$.data.conteudo[1].tipo").value("ENTRADA"));

        // Um unico dia tambem entra inteiro: de = ate = 2026-03-12 pega a linha das 23:30.
        mockMvc.perform(get(BASE + "?de=2026-03-12&ate=2026-03-12")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].tipo").value("SAIDA"));

        // So `de` (aberto a direita) e so `ate` (aberto a esquerda) tambem funcionam.
        mockMvc.perform(get(BASE + "?de=2026-03-10").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.totalElementos").value(3));
        mockMvc.perform(get(BASE + "?ate=2026-03-09").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(jsonPath("$.data.totalElementos").value(1));
    }

    // ---- CA-18: E entre parametros diferentes (a prova do filtro COMBINADO) ----------------------

    @Test
    void tipoEProdutoId_combinamComE_reduzindoOResultado() throws Exception {
        lancamento(rosa, "SAIDA", "2026-03-10 09:00:00"); // o unico que casa os DOIS
        lancamento(rosa, "ENTRADA", "2026-03-10 10:00:00");
        lancamento(lirio, "SAIDA", "2026-03-10 11:00:00");
        lancamento(lirio, "ENTRADA", "2026-03-10 12:00:00");

        // Cada filtro SOZINHO devolve 2 — e o que torna o combinado falseavel: se um deles fosse
        // ignorado, o resultado abaixo seria 2, nao 1.
        mockMvc.perform(get(BASE + "?tipo=SAIDA").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(2));
        mockMvc.perform(get(BASE + "?produtoId=" + rosa).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(2));

        // Combinados: E, nao OU. totalElementos = 1 e o conteudo e exatamente a intersecao.
        mockMvc.perform(get(BASE + "?tipo=SAIDA&produtoId=" + rosa)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo.length()").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].tipo").value("SAIDA"))
                .andExpect(jsonPath("$.data.conteudo[0].produtoNome").value("Rosa Vermelha"));

        // O totalElementos (countQuery) bate com a contagem REAL do recorte no banco.
        Integer contagemReal = jdbc.queryForObject(
                "SELECT count(*) FROM movimentacao_estoque WHERE tipo = 'SAIDA' AND produto_id = ?",
                Integer.class, rosa);
        assertEquals(1, contagemReal);

        // Tres filtros juntos (periodo + tipo + produto) continuam em E.
        mockMvc.perform(get(BASE + "?de=2026-03-11&tipo=SAIDA&produtoId=" + rosa)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(0))
                .andExpect(jsonPath("$.data.conteudo.length()").value(0));
    }

    // ---- CA-18 (contraparte): clienteId e fornecedorId, e o E entre eles -------------------------

    @Test
    void clienteIdEFornecedorId_filtramAContraparteEmE() throws Exception {
        lancamentoComContraparte(
                rosa, "ENTRADA", "2026-03-10 09:00:00", "fornecedor", fornecedor, "Sitio Boa Flor");
        lancamentoComContraparte(
                lirio, "SAIDA", "2026-03-10 10:00:00", "cliente", cliente, "Maria das Flores");
        lancamento(rosa, "AJUSTE", "2026-03-10 11:00:00"); // sem contraparte

        mockMvc.perform(get(BASE + "?fornecedorId=" + fornecedor)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].fornecedorNome").value("Sitio Boa Flor"));

        mockMvc.perform(get(BASE + "?clienteId=" + cliente)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].clienteNome").value("Maria das Flores"));

        // E entre contraparte e produto: o fornecedor existe, mas nao no lirio → vazio.
        mockMvc.perform(get(BASE + "?fornecedorId=" + fornecedor + "&produtoId=" + lirio)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(0));

        // tipo=AJUSTE isola a linha sem contraparte.
        mockMvc.perform(get(BASE + "?tipo=AJUSTE").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].fornecedorId").doesNotExist())
                .andExpect(jsonPath("$.data.conteudo[0].clienteId").doesNotExist());
    }

    // ---- CA-18: tipo fora do conjunto → 400 field=tipo; ?produtoId=abc → 400 (M6.1/D3) -----------

    @Test
    void tipoForaDoConjunto_devolve400ComFieldTipo() throws Exception {
        mockMvc.perform(get(BASE + "?tipo=XPTO").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("tipo"));

        // Minusculo tambem esta fora do conjunto (o CHECK do banco e maiusculo).
        mockMvc.perform(get(BASE + "?tipo=saida").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("tipo"));
    }

    @Test
    void parametroDeTipoErrado_continua400_naoRegridiuOM61() throws Exception {
        mockMvc.perform(get(BASE + "?produtoId=abc").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("produtoId"));

        // Data que nao e data cai no mesmo caminho (nunca 500).
        mockMvc.perform(get(BASE + "?de=10/03/2026").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("de"));
    }

    // ---- CA-19: de > ate → 400 field=de ---------------------------------------------------------

    @Test
    void dataInicialMaiorQueFinal_devolve400ComFieldDe() throws Exception {
        mockMvc.perform(get(BASE + "?de=2026-03-12&ate=2026-03-10")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("de"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("A data inicial não pode ser maior que a final."));

        // de == ate e valido (dia unico) — a fronteira do lado que PASSA.
        mockMvc.perform(get(BASE + "?de=2026-03-10&ate=2026-03-10")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
    }
}
