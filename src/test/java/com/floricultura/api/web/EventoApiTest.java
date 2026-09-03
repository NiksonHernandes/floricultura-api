package com.floricultura.api.web;

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
 * Integracao do CRUD de eventos do M4 (T-M4-2, CA-1..CA-8) contra um PostgreSQL de DESCARTE
 * (Testcontainers postgres:16), com {@code EventoController}/{@code EventoService}, {@code
 * SecurityConfig} endurecido (matchers §3.6) e filtro JWT reais. Prova o RBAC por metodo
 * (POST/PUT/DELETE = ADMIN; USER → 403; sem token → 401), a validacao (enum/datas → 400 com {@code
 * details}), {@code dataUnica} computado, o filtro/ordenacao da lista e o hard delete com cascade.
 *
 * <p>Nome {@code *Test} (Surefire): o repo <b>nao</b> configura Failsafe — integracoes Testcontainers
 * usam {@code *Test}. Segredos de teste sao NAO-segredos (BCrypt de {@link UUID} de runtime).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EventoApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-eventoapi-0123456789abcd");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String userBearer;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM evento_produto");
        jdbc.update("DELETE FROM evento");
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("admin-evento@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("user-evento@floricultura.local", "USER");
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

    private Long inserirEvento(String nome, String dataInicio, String dataFim, String tipo) {
        return jdbc.queryForObject(
                "INSERT INTO evento (nome, data_inicio, data_fim, tipo, repete_todo_ano) "
                        + "VALUES (?, ?::date, ?::date, ?, false) RETURNING id",
                Long.class, nome, dataInicio, dataFim, tipo);
    }

    // ---- CA-1: POST ADMIN 201 (dataFim omitido ⇒ dataUnica=true) ------------------------------

    @Test
    void criar_dataUnica_comAdmin_devolve201() throws Exception {
        String body = """
                {"nome":"Dia das Maes","tipo":"COMEMORATIVA","dataInicio":"2026-05-10",
                 "repeteTodoAno":true,"descricao":"Pico de arranjos"}""";

        mockMvc.perform(post("/api/v1/eventos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.nome").value("Dia das Maes"))
                .andExpect(jsonPath("$.data.tipo").value("COMEMORATIVA"))
                .andExpect(jsonPath("$.data.dataInicio").value("2026-05-10"))
                .andExpect(jsonPath("$.data.dataFim").doesNotExist())
                .andExpect(jsonPath("$.data.dataUnica").value(true))
                .andExpect(jsonPath("$.data.repeteTodoAno").value(true))
                .andExpect(jsonPath("$.data.criadoEm").isNotEmpty());
    }

    // ---- CA-2: periodo valido 201 (dataUnica=false) / dataFim<dataInicio 400 field=dataFim ----

    @Test
    void criar_periodoValido_devolve201ComDataUnicaFalse() throws Exception {
        String body = """
                {"nome":"Festa das Flores","tipo":"FEIRA","dataInicio":"2026-09-04",
                 "dataFim":"2026-09-13"}""";

        mockMvc.perform(post("/api/v1/eventos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dataFim").value("2026-09-13"))
                .andExpect(jsonPath("$.data.dataUnica").value(false))
                .andExpect(jsonPath("$.data.repeteTodoAno").value(false));
    }

    @Test
    void criar_dataFimAntesDeInicio_devolve400FieldDataFim() throws Exception {
        String body = """
                {"nome":"Invertido","tipo":"FEIRA","dataInicio":"2026-09-13",
                 "dataFim":"2026-09-04"}""";

        mockMvc.perform(post("/api/v1/eventos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("dataFim"));
    }

    // ---- CA-3: enum invalido / nome em branco → 400 -------------------------------------------

    @Test
    void criar_tipoForaDoEnum_devolve400() throws Exception {
        String body = """
                {"nome":"Qualquer","tipo":"OUTRO","dataInicio":"2026-05-10"}""";

        mockMvc.perform(post("/api/v1/eventos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("tipo"));
    }

    @Test
    void criar_nomeVazio_devolve400() throws Exception {
        String body = """
                {"nome":"","tipo":"COMEMORATIVA","dataInicio":"2026-05-10"}""";

        mockMvc.perform(post("/api/v1/eventos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("nome"));
    }

    // ---- CA-4: lista paginada + filtro nome + ordenacao / tamanho invalido 400 ----------------

    @Test
    void listar_comUser_filtraNomeEOrdenaPorDataInicio() throws Exception {
        inserirEvento("Natal", "2026-12-25", null, "COMEMORATIVA");
        inserirEvento("Dia das Maes", "2026-05-10", null, "COMEMORATIVA");
        inserirEvento("Dia dos Pais", "2026-08-09", null, "COMEMORATIVA");

        mockMvc.perform(get("/api/v1/eventos?pagina=0&tamanho=20&nome=dia")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(2))
                // dataInicio ASC → Maes (05-10) antes de Pais (08-09).
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Dia das Maes"))
                .andExpect(jsonPath("$.data.conteudo[1].nome").value("Dia dos Pais"));
    }

    @Test
    void listar_tamanhoAcimaDoMaximo_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/eventos?tamanho=101")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("tamanho"));
    }

    // ---- CA-5: detalhe existente 200 / inexistente 404 ----------------------------------------

    @Test
    void detalhar_existente_devolve200() throws Exception {
        Long id = inserirEvento("Finados", "2026-11-02", null, "COMEMORATIVA");

        mockMvc.perform(get("/api/v1/eventos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.nome").value("Finados"))
                .andExpect(jsonPath("$.data.dataUnica").value(true));
    }

    @Test
    void detalhar_inexistente_devolve404() throws Exception {
        mockMvc.perform(get("/api/v1/eventos/999999")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- CA-6: PUT ADMIN 200 / inexistente 404 ------------------------------------------------

    @Test
    void atualizar_comAdmin_devolve200ComCamposAtualizados() throws Exception {
        Long id = inserirEvento("Rascunho", "2026-05-10", null, "COMEMORATIVA");
        String body = """
                {"nome":"Dia das Maes","tipo":"COMEMORATIVA","dataInicio":"2026-05-10",
                 "dataFim":"2026-05-11","repeteTodoAno":true}""";

        mockMvc.perform(put("/api/v1/eventos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nome").value("Dia das Maes"))
                .andExpect(jsonPath("$.data.dataFim").value("2026-05-11"))
                .andExpect(jsonPath("$.data.dataUnica").value(false))
                .andExpect(jsonPath("$.data.repeteTodoAno").value(true));
    }

    @Test
    void atualizar_inexistente_devolve404() throws Exception {
        String body = """
                {"nome":"Fantasma","tipo":"FEIRA","dataInicio":"2026-05-10"}""";

        mockMvc.perform(put("/api/v1/eventos/999999")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- CA-7: DELETE ADMIN 204 (cascade em evento_produto) / inexistente 404 -----------------

    @Test
    void deletar_comAdmin_devolve204ERemoveVinculos() throws Exception {
        Long eventoId = inserirEvento("Dia dos Namorados", "2026-06-12", null, "COMEMORATIVA");
        Long produtoId = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES ('Rosa', 'un') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO evento_produto (evento_id, produto_id) VALUES (?, ?)",
                eventoId, produtoId);

        mockMvc.perform(delete("/api/v1/eventos/" + eventoId)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        Integer eventos = jdbc.queryForObject(
                "SELECT count(*) FROM evento WHERE id = ?", Integer.class, eventoId);
        Integer vinculos = jdbc.queryForObject(
                "SELECT count(*) FROM evento_produto WHERE evento_id = ?", Integer.class, eventoId);
        Integer produtos = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE id = ?", Integer.class, produtoId);
        org.junit.jupiter.api.Assertions.assertEquals(0, eventos);
        org.junit.jupiter.api.Assertions.assertEquals(0, vinculos);
        org.junit.jupiter.api.Assertions.assertEquals(1, produtos); // produto preservado (CA-7)
    }

    @Test
    void deletar_inexistente_devolve404() throws Exception {
        mockMvc.perform(delete("/api/v1/eventos/999999")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- CA-8: RBAC — USER 403 na escrita / sem token 401 -------------------------------------

    @Test
    void criar_comUser_devolve403() throws Exception {
        String body = """
                {"nome":"Feira","tipo":"FEIRA","dataInicio":"2026-05-10"}""";

        mockMvc.perform(post("/api/v1/eventos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void atualizar_comUser_devolve403() throws Exception {
        Long id = inserirEvento("Feira", "2026-05-10", null, "FEIRA");
        String body = """
                {"nome":"Feira X","tipo":"FEIRA","dataInicio":"2026-05-10"}""";

        mockMvc.perform(put("/api/v1/eventos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void deletar_comUser_devolve403() throws Exception {
        Long id = inserirEvento("Feira", "2026-05-10", null, "FEIRA");

        mockMvc.perform(delete("/api/v1/eventos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void criar_semToken_devolve401() throws Exception {
        mockMvc.perform(post("/api/v1/eventos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Feira\",\"tipo\":\"FEIRA\",\"dataInicio\":\"2026-05-10\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
