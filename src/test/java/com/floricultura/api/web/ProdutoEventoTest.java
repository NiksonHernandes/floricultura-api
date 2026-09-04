package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Integracao do vinculo N:N produto↔evento do M4 (T-M4-3, CA-9/CA-10/CA-11) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16). Prova: replace-set de {@code eventoIds} no POST/PUT de
 * produto; {@code sazonal} na lista (true/false) via @Formula; {@code eventoIds} no detalhe (null na
 * lista); cascade dos dois lados (deletar produto/evento remove so o link); id inexistente → 400
 * {@code field=eventoIds}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoEventoTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-produtoevento-0123456789");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM evento_produto");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM evento");
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("admin-pe@floricultura.local", "ADMIN");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    private Long inserirEvento(String nome, String dataInicio) {
        return jdbc.queryForObject(
                "INSERT INTO evento (nome, data_inicio, tipo, repete_todo_ano) "
                        + "VALUES (?, ?::date, 'COMEMORATIVA', false) RETURNING id",
                Long.class, nome, dataInicio);
    }

    // ---- CA-9: POST com eventoIds → vinculos persistidos; detalhe traz eventoIds; lista sazonal --

    @Test
    void criar_comEventoIds_persisteVinculosEDetalheTrazEventoIds() throws Exception {
        Long ev1 = inserirEvento("Dia das Maes", "2026-05-10");
        Long ev2 = inserirEvento("Dia dos Namorados", "2026-06-12");

        String body = """
                {"nome":"Buque Especial","unidadeMedida":"un","estoqueMinimo":1,
                 "eventoIds":[%d,%d]}""".formatted(ev1, ev2);

        String location = mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sazonal").value(true))
                .andExpect(jsonPath("$.data.eventoIds").isArray())
                .andExpect(jsonPath("$.data.eventoIds.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        Long produtoId = com.jayway.jsonpath.JsonPath.parse(location).read("$.data.id", Long.class);

        // Vinculos persistidos no banco.
        Integer links = jdbc.queryForObject(
                "SELECT count(*) FROM evento_produto WHERE produto_id = ?", Integer.class, produtoId);
        assertEquals(2, links);

        // GET /{id} retorna eventoIds.
        mockMvc.perform(get("/api/v1/produtos/" + produtoId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sazonal").value(true))
                .andExpect(jsonPath("$.data.eventoIds.length()").value(2));
    }

    @Test
    void lista_sazonalTrueParaVinculadoEFalseParaSemVinculo_eventoIdsNull() throws Exception {
        Long ev = inserirEvento("Natal", "2026-12-25");
        // Produto A vinculado.
        String bodyA = """
                {"nome":"Arranjo A","unidadeMedida":"un","estoqueMinimo":1,"eventoIds":[%d]}"""
                .formatted(ev);
        mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA))
                .andExpect(status().isCreated());
        // Produto B sem vinculo.
        mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Arranjo B\",\"unidadeMedida\":\"un\",\"estoqueMinimo\":1}"))
                .andExpect(status().isCreated());

        // Lista ordenada por nome ASC: A (sazonal true) antes de B (false); eventoIds null na lista.
        mockMvc.perform(get("/api/v1/produtos?nome=Arranjo")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Arranjo A"))
                .andExpect(jsonPath("$.data.conteudo[0].sazonal").value(true))
                .andExpect(jsonPath("$.data.conteudo[0].eventoIds").doesNotExist())
                .andExpect(jsonPath("$.data.conteudo[1].nome").value("Arranjo B"))
                .andExpect(jsonPath("$.data.conteudo[1].sazonal").value(false));
    }

    @Test
    void atualizar_substituiConjuntoDeVinculos() throws Exception {
        Long ev1 = inserirEvento("Evento 1", "2026-01-10");
        Long ev2 = inserirEvento("Evento 2", "2026-02-10");
        Long produtoId = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo) "
                        + "VALUES ('Produto X', 'un', 1) RETURNING id", Long.class);
        jdbc.update("INSERT INTO evento_produto (evento_id, produto_id) VALUES (?, ?)", ev1, produtoId);

        // PUT com eventoIds=[ev2] substitui o conjunto (remove ev1, adiciona ev2).
        String body = """
                {"nome":"Produto X","unidadeMedida":"un","estoqueMinimo":1,"eventoIds":[%d]}"""
                .formatted(ev2);
        mockMvc.perform(put("/api/v1/produtos/" + produtoId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventoIds.length()").value(1))
                .andExpect(jsonPath("$.data.eventoIds[0]").value(ev2));

        Long restante = jdbc.queryForObject(
                "SELECT evento_id FROM evento_produto WHERE produto_id = ?", Long.class, produtoId);
        assertEquals(ev2, restante);
    }

    @Test
    void atualizar_comEventoIdsVazio_removeTodosOsVinculos() throws Exception {
        Long ev = inserirEvento("Evento", "2026-01-10");
        Long produtoId = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo) "
                        + "VALUES ('Produto Y', 'un', 1) RETURNING id", Long.class);
        jdbc.update("INSERT INTO evento_produto (evento_id, produto_id) VALUES (?, ?)", ev, produtoId);

        mockMvc.perform(put("/api/v1/produtos/" + produtoId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Produto Y\",\"unidadeMedida\":\"un\","
                                + "\"estoqueMinimo\":1,\"eventoIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sazonal").value(false))
                .andExpect(jsonPath("$.data.eventoIds.length()").value(0));

        Integer links = jdbc.queryForObject(
                "SELECT count(*) FROM evento_produto WHERE produto_id = ?", Integer.class, produtoId);
        assertEquals(0, links);
    }

    // ---- CA-10: cascade dos dois lados -------------------------------------------------------

    @Test
    void hardDeleteDeProduto_removeVinculoEPreservaEvento() throws Exception {
        Long ev = inserirEvento("Pascoa", "2026-04-05");
        Long produtoId = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo) "
                        + "VALUES ('Ovo', 'un', 1) RETURNING id", Long.class);
        jdbc.update("INSERT INTO evento_produto (evento_id, produto_id) VALUES (?, ?)", ev, produtoId);

        mockMvc.perform(delete("/api/v1/produtos/" + produtoId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        Integer links = jdbc.queryForObject(
                "SELECT count(*) FROM evento_produto WHERE evento_id = ?", Integer.class, ev);
        Integer eventos = jdbc.queryForObject(
                "SELECT count(*) FROM evento WHERE id = ?", Integer.class, ev);
        assertEquals(0, links);
        assertEquals(1, eventos); // evento preservado
    }

    // ---- CA-11: eventoIds com id inexistente → 400 field=eventoIds, nada persiste --------------

    @Test
    void criar_comEventoIdInexistente_devolve400ENaoPersiste() throws Exception {
        String body = """
                {"nome":"Produto Z","unidadeMedida":"un","estoqueMinimo":1,"eventoIds":[999999]}""";

        mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("eventoIds"));

        // Nada persistido: nenhum produto criado, nenhum vinculo.
        Integer produtos = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE nome = 'Produto Z'", Integer.class);
        Integer links = jdbc.queryForObject("SELECT count(*) FROM evento_produto", Integer.class);
        assertEquals(0, produtos);
        assertEquals(0, links);
    }
}
