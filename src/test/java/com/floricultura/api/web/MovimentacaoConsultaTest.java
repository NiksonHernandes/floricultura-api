package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Integracao da autoria + lista global de movimentacoes do M4 (T-M4-10, CA-19..CA-23) contra um
 * PostgreSQL de DESCARTE (Testcontainers postgres:16). Prova: {@code usuario_nome} gravado do principal
 * (nunca do payload); autor sobrevive ao hard delete do usuario ({@code usuario_id=NULL},
 * {@code usuario_nome} intacto); linha pre-V7 ({@code usuario_nome=NULL}) → {@code usuarioNome:null};
 * lista global filtrando produto OU autor, ordem {@code criado_em DESC}; USER 200 / sem token 401;
 * {@code tamanho=101}/{@code pagina=-1} → 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MovimentacaoConsultaTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-movconsulta-0123456789ab");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long adminId;
    private String adminBearer;
    private String userBearer;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        adminId = inserirUsuario("Ana", "admin-mc@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("Beto", "user-mc@floricultura.local", "USER");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
        userBearer = "Bearer " + jwtService.gerarToken(userId);
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
                Long.class, nome, new java.math.BigDecimal(estoqueAtual));
    }

    // ---- CA-19: usuario_nome gravado do principal (nao do payload); resposta traz usuarioNome ----

    @Test
    void movimentar_gravaUsuarioNomeDoPrincipal_ignorandoPayload() throws Exception {
        Long id = inserirProduto("Rosa Vermelha", "0");
        // Payload tenta injetar autor falso — deve ser IGNORADO (autor vem do @AuthenticationPrincipal).
        String body = """
                {"tipo":"ENTRADA","quantidade":30,"motivo":"Compra",
                 "usuarioId":999,"usuarioNome":"Fraudador"}""";

        mockMvc.perform(post("/api/v1/produtos/" + id + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.usuarioId").value(adminId))
                .andExpect(jsonPath("$.data.usuarioNome").value("Ana"));

        String nomeLedger = jdbc.queryForObject(
                "SELECT usuario_nome FROM movimentacao_estoque WHERE produto_id = ?",
                String.class, id);
        assertEquals("Ana", nomeLedger); // do principal, nunca "Fraudador"
    }

    // ---- CA-20: o snapshot do autor sobrevive a remocao (desativacao) do usuario --------------
    // Nota: o app NAO faz hard delete de usuario (so soft delete, ativo=false); um DELETE direto seria
    // barrado pela trigger de imutabilidade (o cascade usuario_id->NULL e um UPDATE do ledger — invariante
    // §6). O que CA-20 garante e a SOBREVIVENCIA DA AUDITORIA: o usuario_nome e snapshot desnormalizado,
    // independente do estado do usuario. Ver relatorio (tensao com a redacao literal da spec/AD-SQ-45).

    @Test
    void snapshotDoAutorSobreviveADesativacaoDoUsuario() throws Exception {
        Long id = inserirProduto("Lirio", "0");
        mockMvc.perform(post("/api/v1/produtos/" + id + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"ENTRADA\",\"quantidade\":10}"))
                .andExpect(status().isCreated());

        // Remocao real do autor no sistema = desativacao (soft delete). O ledger nao e tocado.
        jdbc.update("UPDATE usuario SET ativo = false WHERE id = ?", adminId);

        // A resposta ainda mostra o nome do autor (snapshot desnormalizado — auditoria sobrevive).
        mockMvc.perform(get("/api/v1/movimentacoes?q=Lirio")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo[0].usuarioNome").value("Ana"));
    }

    // ---- CA-21: linha pre-V7 (usuario_nome NULL) → usuarioNome:null na API --------------------

    @Test
    void linhaSemAutor_devolveUsuarioNomeNull() throws Exception {
        Long id = inserirProduto("Cravo", "0");
        // Simula linha historica pre-V7: INSERT direto sem usuario_nome.
        jdbc.update("INSERT INTO movimentacao_estoque "
                + "(produto_id, produto_nome, tipo, quantidade, quantidade_resultante) "
                + "VALUES (?, 'Cravo', 'ENTRADA', 5, 5)", id);

        mockMvc.perform(get("/api/v1/movimentacoes?q=Cravo")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo[0].produtoNome").value("Cravo"))
                .andExpect(jsonPath("$.data.conteudo[0].usuarioNome").doesNotExist());
    }

    // ---- CA-22: lista global filtra produto OU autor, ordem criado_em DESC; USER 200 / 401 -----

    @Test
    void listaGlobal_filtraPorProdutoOuAutor_ordemDesc() throws Exception {
        Long rosa = inserirProduto("Rosa", "0");
        Long lirio = inserirProduto("Lirio", "0");
        // adminId=Ana movimenta Rosa; depois Ana movimenta Lirio (mais recente).
        movimentar(rosa, adminBearer, "ENTRADA", "5");
        movimentar(lirio, adminBearer, "ENTRADA", "7");

        // q=rosa casa pelo produto_nome.
        mockMvc.perform(get("/api/v1/movimentacoes?q=rosa")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].produtoNome").value("Rosa"));

        // q=ana casa pelo usuario_nome (autor) — as duas movimentacoes, ordem criado_em DESC (Lirio 1o).
        mockMvc.perform(get("/api/v1/movimentacoes?q=ana")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(2))
                .andExpect(jsonPath("$.data.conteudo[0].produtoNome").value("Lirio"))
                .andExpect(jsonPath("$.data.conteudo[0].usuarioNome").value("Ana"))
                .andExpect(jsonPath("$.data.conteudo[1].produtoNome").value("Rosa"));

        // Sem q: retorna tudo.
        mockMvc.perform(get("/api/v1/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(2));
    }

    @Test
    void listaGlobal_semToken_devolve401() throws Exception {
        mockMvc.perform(get("/api/v1/movimentacoes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---- CA-23: paginacao obrigatoria — tamanho=101 e pagina=-1 → 400 -------------------------

    @Test
    void listaGlobal_tamanhoAcimaDoMaximo_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/movimentacoes?tamanho=101")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("tamanho"));
    }

    @Test
    void listaGlobal_paginaNegativa_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/movimentacoes?pagina=-1")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("pagina"));
    }

    private void movimentar(Long produtoId, String bearer, String tipo, String quantidade)
            throws Exception {
        mockMvc.perform(post("/api/v1/produtos/" + produtoId + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"" + tipo + "\",\"quantidade\":" + quantidade + "}"))
                .andExpect(status().isCreated());
    }
}
