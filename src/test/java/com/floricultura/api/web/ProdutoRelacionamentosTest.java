package com.floricultura.api.web;

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
 * Integracao do endpoint <b>{@code GET /api/v1/produtos/{id}/relacionamentos}</b> (RB-4, R-CA-10 —
 * SPEC-M5 §R3.5, AD-SQ-66) contra um PostgreSQL de DESCARTE (Testcontainers postgres:16), com
 * {@code ProdutoController}/{@code ProdutoService}, filtro JWT e Flyway V1..V10 reais. Prova: os tres
 * grupos (eventos/fornecedores/clientes) derivados, deduplicados e limitados aos <b>3 mais recentes</b>
 * (M5.1/HISTORIA #6, T-M5.1-7, AD-SQ-73) — fornecedores/clientes por {@code MAX(criado_em) DESC LIMIT 3}
 * (recencia real do ledger); eventos por {@code id DESC LIMIT 3} (proxy, sem timestamp por vinculo);
 * listas vazias quando nao ha relacoes; 404 para produto inexistente; RBAC (USER 200, sem token 401); e
 * o comportamento do §R3.5 no hard delete — o JOIN na <b>tabela viva</b> oculta o cadastro deletado e
 * mantem os vivos (id sempre nao-nulo). Dados ficticios (LGPD).
 *
 * <p><b>Mudanca de contrato deliberada (T-M5.1-7, AD-SQ-73, autorizada pelo dono no gate):</b> a ordem
 * das 3 queries de {@code relacionamentos} passou de {@code nome ASC}/lista completa para
 * <b>recencia DESC + LIMIT 3</b>. As assercoes de ordem/limite abaixo refletem esse novo contrato — nao
 * e afrouxamento, e a nova definicao de pronto do SDD.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoRelacionamentosTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-relacionamentos-0123456789");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String userBearer;
    private Long produtoId;

    @BeforeEach
    void seed() {
        // TRUNCATE do ledger ANTES de apagar cadastros (cascade SET NULL = UPDATE barrado pela trigger).
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM evento_produto");
        jdbc.update("DELETE FROM evento");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM cliente");
        jdbc.update("DELETE FROM fornecedor");
        jdbc.update("DELETE FROM usuario");
        Long userId = inserirUsuario("user-relac@floricultura.local", "USER");
        userBearer = "Bearer " + jwtService.gerarToken(userId);
        produtoId = inserirProduto("Rosa");
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    private Long inserirEvento(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO evento (nome, data_inicio, tipo) "
                        + "VALUES (?, DATE '2026-05-10', 'COMEMORATIVA') RETURNING id",
                Long.class, nome);
    }

    private void vincularEvento(Long eventoId, Long produtoId) {
        jdbc.update("INSERT INTO evento_produto (evento_id, produto_id) VALUES (?, ?)",
                eventoId, produtoId);
    }

    private Long inserirFornecedor(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO fornecedor (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    private Long inserirCliente(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO cliente (nome) VALUES (?) RETURNING id", Long.class, nome);
    }

    private void inserirEntrada(Long produtoId, Long fornecedorId, String fornecedorNome) {
        jdbc.update("INSERT INTO movimentacao_estoque "
                + "(produto_id, produto_nome, tipo, quantidade, quantidade_resultante, "
                + " fornecedor_id, fornecedor_nome) "
                + "VALUES (?, 'Rosa', 'ENTRADA', 1, 1, ?, ?)", produtoId, fornecedorId, fornecedorNome);
    }

    private void inserirSaida(Long produtoId, Long clienteId, String clienteNome) {
        jdbc.update("INSERT INTO movimentacao_estoque "
                + "(produto_id, produto_nome, tipo, quantidade, quantidade_resultante, "
                + " cliente_id, cliente_nome) "
                + "VALUES (?, 'Rosa', 'SAIDA', 1, 0, ?, ?)", produtoId, clienteId, clienteNome);
    }

    /** ENTRADA com {@code criado_em} explicito — controla a recencia do ledger para as assercoes de ordem. */
    private void inserirEntradaEm(Long produtoId, Long fornecedorId, String fornecedorNome, String criadoEm) {
        jdbc.update("INSERT INTO movimentacao_estoque "
                + "(produto_id, produto_nome, tipo, quantidade, quantidade_resultante, "
                + " fornecedor_id, fornecedor_nome, criado_em) "
                + "VALUES (?, 'Rosa', 'ENTRADA', 1, 1, ?, ?, ?::timestamptz)",
                produtoId, fornecedorId, fornecedorNome, criadoEm);
    }

    /** SAIDA com {@code criado_em} explicito — controla a recencia do ledger para as assercoes de ordem. */
    private void inserirSaidaEm(Long produtoId, Long clienteId, String clienteNome, String criadoEm) {
        jdbc.update("INSERT INTO movimentacao_estoque "
                + "(produto_id, produto_nome, tipo, quantidade, quantidade_resultante, "
                + " cliente_id, cliente_nome, criado_em) "
                + "VALUES (?, 'Rosa', 'SAIDA', 1, 0, ?, ?, ?::timestamptz)",
                produtoId, clienteId, clienteNome, criadoEm);
    }

    // ---- CA-21 (novo contrato, T-M5.1-7/AD-SQ-73): os tres grupos vem deduplicados, limitados aos
    // ---- 3 MAIS RECENTES e ordenados por recencia DESC (nao mais nome ASC). Com >3 itens de cada, o
    // ---- mais antigo cai fora (LIMIT 3); o nome mais recente vem primeiro, PROVANDO que a ordem e por
    // ---- recencia (nao alfabetica). Eventos usam id DESC como proxy (sem timestamp por vinculo). ----

    @Test
    void relacionamentos_devolveTresMaisRecentes_ordemPorRecenciaDesc() throws Exception {
        // Eventos: 4 vinculados, cadastrados em ordem de id crescente. Proxy = id DESC LIMIT 3 →
        // os 3 ultimos cadastrados (Finados, Pascoa, Maes); o primeiro (Natal) cai fora.
        Long eventoNatal = inserirEvento("Natal");   // id mais baixo → excluido pelo LIMIT 3
        Long eventoMaes = inserirEvento("Dia das Maes");
        Long eventoPascoa = inserirEvento("Pascoa");
        Long eventoFinados = inserirEvento("Finados"); // id mais alto → 1o (mais recente)
        vincularEvento(eventoNatal, produtoId);
        vincularEvento(eventoMaes, produtoId);
        vincularEvento(eventoPascoa, produtoId);
        vincularEvento(eventoFinados, produtoId);

        // Fornecedores: 4 ENTRADAS com criado_em distintos. "Zeta" e a MAIS RECENTE (alfabeticamente a
        // ultima) → deve vir 1a, provando recencia ≠ nome. "Alpha" e a mais antiga → cai pelo LIMIT 3.
        Long fornAlpha = inserirFornecedor("Alpha Norte");
        Long fornBeta = inserirFornecedor("Beta Sul");
        Long fornGama = inserirFornecedor("Gama Leste");
        Long fornZeta = inserirFornecedor("Zeta Oeste");
        inserirEntradaEm(produtoId, fornAlpha, "Alpha Norte", "2026-01-01T09:00:00Z");
        inserirEntradaEm(produtoId, fornBeta, "Beta Sul", "2026-02-01T09:00:00Z");
        inserirEntradaEm(produtoId, fornGama, "Gama Leste", "2026-03-01T09:00:00Z");
        inserirEntradaEm(produtoId, fornZeta, "Zeta Oeste", "2026-04-01T09:00:00Z");

        // Clientes: espelha fornecedores. "Yara" mais recente → 1a; "Ana" mais antiga → excluida.
        Long cliAna = inserirCliente("Ana Silva");
        Long cliBruno = inserirCliente("Bruno Costa");
        Long cliMarcos = inserirCliente("Marcos Dias");
        Long cliYara = inserirCliente("Yara Luz");
        inserirSaidaEm(produtoId, cliAna, "Ana Silva", "2026-01-01T09:00:00Z");
        inserirSaidaEm(produtoId, cliBruno, "Bruno Costa", "2026-02-01T09:00:00Z");
        inserirSaidaEm(produtoId, cliMarcos, "Marcos Dias", "2026-03-01T09:00:00Z");
        inserirSaidaEm(produtoId, cliYara, "Yara Luz", "2026-04-01T09:00:00Z");

        mockMvc.perform(get("/api/v1/produtos/" + produtoId + "/relacionamentos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                // eventos: (a) no maximo 3; (b) ordem id DESC → Finados, Pascoa, Maes; Natal excluido.
                .andExpect(jsonPath("$.data.eventos.length()").value(3))
                .andExpect(jsonPath("$.data.eventos[0].id").value(eventoFinados))
                .andExpect(jsonPath("$.data.eventos[0].nome").value("Finados"))
                .andExpect(jsonPath("$.data.eventos[1].id").value(eventoPascoa))
                .andExpect(jsonPath("$.data.eventos[2].id").value(eventoMaes))
                // fornecedores: (a) no maximo 3; (b) recencia DESC → Zeta, Gama, Beta; Alpha excluido.
                .andExpect(jsonPath("$.data.fornecedores.length()").value(3))
                .andExpect(jsonPath("$.data.fornecedores[0].id").value(fornZeta))
                .andExpect(jsonPath("$.data.fornecedores[0].nome").value("Zeta Oeste"))
                .andExpect(jsonPath("$.data.fornecedores[1].id").value(fornGama))
                .andExpect(jsonPath("$.data.fornecedores[2].id").value(fornBeta))
                // clientes: (a) no maximo 3; (b) recencia DESC → Yara, Marcos, Bruno; Ana excluida.
                .andExpect(jsonPath("$.data.clientes.length()").value(3))
                .andExpect(jsonPath("$.data.clientes[0].id").value(cliYara))
                .andExpect(jsonPath("$.data.clientes[0].nome").value("Yara Luz"))
                .andExpect(jsonPath("$.data.clientes[1].id").value(cliMarcos))
                .andExpect(jsonPath("$.data.clientes[2].id").value(cliBruno));
    }

    // ---- CA-21 (borda): dedup por GROUP BY + a recencia usa MAX(criado_em). Um fornecedor com DUAS
    // ---- ENTRADAS (uma antiga, uma a mais recente de todas) aparece UMA vez e vem PRIMEIRO — provando
    // ---- que o ranking usa o MAX (a mov mais recente), nao a primeira/qualquer, e que dedup persiste.

    @Test
    void relacionamentos_fornecedorComVariasEntradas_deduplicaEUsaMaxCriadoEm() throws Exception {
        Long fornSul = inserirFornecedor("Sul Flores");
        Long fornNorte = inserirFornecedor("Norte Flores");
        // Sul: uma entrada antiga E a mais recente de todas → MAX(Sul) e o topo, aparece 1x.
        inserirEntradaEm(produtoId, fornSul, "Sul Flores", "2026-01-01T09:00:00Z");
        inserirEntradaEm(produtoId, fornSul, "Sul Flores", "2026-12-01T09:00:00Z");
        // Norte: entrada intermediaria.
        inserirEntradaEm(produtoId, fornNorte, "Norte Flores", "2026-06-01T09:00:00Z");

        mockMvc.perform(get("/api/v1/produtos/" + produtoId + "/relacionamentos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                // dedup: Sul aparece 1x (2 entradas) → total 2 fornecedores.
                .andExpect(jsonPath("$.data.fornecedores.length()").value(2))
                // MAX(Sul)=dez > MAX(Norte)=jun → Sul primeiro.
                .andExpect(jsonPath("$.data.fornecedores[0].id").value(fornSul))
                .andExpect(jsonPath("$.data.fornecedores[0].nome").value("Sul Flores"))
                .andExpect(jsonPath("$.data.fornecedores[1].id").value(fornNorte));
    }

    // ---- CA-21 (borda): abaixo do teto — 2 itens devolvem os 2 (o LIMIT 3 nao inventa nem corta). ----

    @Test
    void relacionamentos_abaixoDoTeto_devolveTodosNaRecencia() throws Exception {
        Long fornAntigo = inserirFornecedor("Antigo Flores");
        Long fornNovo = inserirFornecedor("Novo Flores");
        inserirEntradaEm(produtoId, fornAntigo, "Antigo Flores", "2026-01-01T09:00:00Z");
        inserirEntradaEm(produtoId, fornNovo, "Novo Flores", "2026-05-01T09:00:00Z");

        mockMvc.perform(get("/api/v1/produtos/" + produtoId + "/relacionamentos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fornecedores.length()").value(2))
                .andExpect(jsonPath("$.data.fornecedores[0].id").value(fornNovo))
                .andExpect(jsonPath("$.data.fornecedores[1].id").value(fornAntigo));
    }

    // ---- R-CA-10: produto sem relacoes → tres listas vazias -----------------------------------

    @Test
    void relacionamentos_semRelacoes_devolveListasVazias() throws Exception {
        mockMvc.perform(get("/api/v1/produtos/" + produtoId + "/relacionamentos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventos.length()").value(0))
                .andExpect(jsonPath("$.data.fornecedores.length()").value(0))
                .andExpect(jsonPath("$.data.clientes.length()").value(0));
    }

    // ---- R-CA-10: produto inexistente → 404 ---------------------------------------------------

    @Test
    void relacionamentos_produtoInexistente_devolve404() throws Exception {
        mockMvc.perform(get("/api/v1/produtos/999999/relacionamentos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- RBAC: sem token → 401 (USER 200 ja coberto pelos testes acima) -----------------------

    @Test
    void relacionamentos_semToken_devolve401() throws Exception {
        mockMvc.perform(get("/api/v1/produtos/" + produtoId + "/relacionamentos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---- §R3.5: hard delete do cadastro — JOIN na tabela viva oculta o deletado, mantem os vivos.
    // (Comportamento canonico do SPEC: id sempre nao-nulo; o snapshot do ledger fica no /movimentacoes,
    //  nao aqui.) Divergencia sinalizada ao coordenador no relatorio.

    @Test
    void relacionamentos_aposHardDeleteDeFornecedor_ocultaODeletadoMantemVivo() throws Exception {
        Long fornAlpha = inserirFornecedor("Alpha Flores");
        Long fornBeta = inserirFornecedor("Beta Verde");
        inserirEntrada(produtoId, fornAlpha, "Alpha Flores");
        inserirEntrada(produtoId, fornBeta, "Beta Verde");

        // Hard delete de Alpha (cascade SET NULL no ledger; trigger V10 tolera).
        jdbc.update("DELETE FROM fornecedor WHERE id = ?", fornAlpha);

        mockMvc.perform(get("/api/v1/produtos/" + produtoId + "/relacionamentos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                // §R3.5 (tabela viva): so o fornecedor vivo aparece; o deletado sai (id nulo no ledger).
                .andExpect(jsonPath("$.data.fornecedores.length()").value(1))
                .andExpect(jsonPath("$.data.fornecedores[0].id").value(fornBeta))
                .andExpect(jsonPath("$.data.fornecedores[0].nome").value("Beta Verde"));
    }

    @Test
    void relacionamentos_aposHardDeleteDeCliente_ocultaODeletadoMantemVivo() throws Exception {
        Long cliAna = inserirCliente("Ana Silva");
        Long cliBruno = inserirCliente("Bruno Costa");
        inserirSaida(produtoId, cliAna, "Ana Silva");
        inserirSaida(produtoId, cliBruno, "Bruno Costa");

        jdbc.update("DELETE FROM cliente WHERE id = ?", cliAna);

        mockMvc.perform(get("/api/v1/produtos/" + produtoId + "/relacionamentos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientes.length()").value(1))
                .andExpect(jsonPath("$.data.clientes[0].id").value(cliBruno))
                .andExpect(jsonPath("$.data.clientes[0].nome").value("Bruno Costa"));
    }
}
