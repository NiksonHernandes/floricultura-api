package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import com.floricultura.api.web.response.ApiResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integracao ponta a ponta do catalogo de cores (SPEC-M6 §3.2/§3.2.1 — CA-1..CA-7 e CA-39.6), contra
 * PostgreSQL de descarte (Testcontainers) com a V12 aplicada pelo Flyway. A tabela-verdade do
 * {@code canonizar} e o "sem N+1" ficam no {@code CorServiceTest} (unitario); aqui provamos o que so o
 * HTTP mostra: envelope, status, RBAC, Swagger e o que ficou GRAVADO no banco.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CorApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String JSON_CINZA = "{\"nome\":\"  cinza escuro \",\"hex\":\"#c4326b\"}";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-corapi-0123456789abcdef");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CorController corController;

    private String adminBearer;
    private String userBearer;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM produto_cor");
        jdbc.update("DELETE FROM cor");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        adminBearer = "Bearer " + jwtService.gerarToken(
                inserirUsuario("admin-corapi@floricultura.local", "ADMIN"));
        userBearer = "Bearer " + jwtService.gerarToken(
                inserirUsuario("user-corapi@floricultura.local", "USER"));
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email, ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    private Long inserirCor(String nomeCanonico) {
        return jdbc.queryForObject(
                "INSERT INTO cor (nome) VALUES (?) RETURNING id", Long.class, nomeCanonico);
    }

    /** Cria {@code quantos} produtos ficticios e vincula todos a cor (produto_cor). */
    private void vincular(Long corId, int quantos) {
        for (int i = 0; i < quantos; i++) {
            Long produtoId = jdbc.queryForObject(
                    "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                    Long.class, "Vaso " + i + "-" + corId);
            jdbc.update("INSERT INTO produto_cor (produto_id, cor_id) VALUES (?, ?)", produtoId, corId);
        }
    }

    private int contarCores() {
        return jdbc.queryForObject("SELECT count(*) FROM cor", Integer.class);
    }

    // ---- CA-1: POST grava o CANONICO (o front manda cru) ---------------------------------------

    @Test
    void criar_textoCru_devolve201ComCanonicoEGravaCanonico() throws Exception {
        mockMvc.perform(post("/api/v1/cores").header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(JSON_CINZA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.nome").value("CINZA-ESCURO"))
                .andExpect(jsonPath("$.data.hex").value("#C4326B"))
                .andExpect(jsonPath("$.data.produtosVinculados").value(0))
                .andExpect(jsonPath("$.data.criadoEm").isNotEmpty())
                .andExpect(jsonPath("$.data.atualizadoEm").isNotEmpty());

        assertEquals("CINZA-ESCURO",
                jdbc.queryForObject("SELECT nome FROM cor", String.class)); // nao o texto cru
    }

    // ---- CA-2: duplicata sobre o canonico → 409 e a tabela nao cresce --------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Azul", "azul", "  AZUL  ", "-azul-"})
    void criar_variacoesDeAzulComAzulExistente_devolve409ESoUmaLinha(String cru) throws Exception {
        inserirCor("AZUL");

        mockMvc.perform(post("/api/v1/cores").header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"" + cru + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("Já existe uma cor com esse nome."));

        assertEquals(1, contarCores());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cinza escuro", "CINZA - ESCURO", "CINZA--ESCURO"})
    void criar_variacoesDeCinzaEscuro_devolve409(String cru) throws Exception {
        inserirCor("CINZA-ESCURO");

        mockMvc.perform(post("/api/v1/cores").header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"" + cru + "\"}"))
                .andExpect(status().isConflict());

        assertEquals(1, contarCores());
    }

    // ---- CA-3: hex opcional, validado no DTO e devolvido em maiusculas -------------------------

    @ParameterizedTest
    @ValueSource(strings = {"vermelho", "#FFF"})
    void criar_hexForaDoPadrao_devolve400FieldHex(String hex) throws Exception {
        mockMvc.perform(post("/api/v1/cores").header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"azul\",\"hex\":\"" + hex + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("hex"));

        assertEquals(0, contarCores());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"nome\":\"azul\"}", "{\"nome\":\"azul\",\"hex\":\"\"}"})
    void criar_hexAusenteOuVazio_devolve201ComHexNulo(String body) throws Exception {
        mockMvc.perform(post("/api/v1/cores").header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nome").value("AZUL"))
                .andExpect(jsonPath("$.data.hex").doesNotExist());
    }

    // ---- CA-39.4/39.5 no HTTP: nome invalido pelo CANONICO → 400 field:"nome", nada persiste ----

    @ParameterizedTest
    @ValueSource(strings = {"---", " - ", "a", "aaaaaaaaaa bbbbbbbbbb cccccccccc dddddddddd eeee"})
    void criar_nomeInvalidoAposCanonizar_devolve400FieldNomeSemPersistir(String cru) throws Exception {
        mockMvc.perform(post("/api/v1/cores").header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"" + cru + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("nome"));

        assertEquals(0, contarCores());
    }

    // ---- CA-4 e CA-39.6: lista nome ASC com produtosVinculados; filtro canoniza o termo ---------

    @Test
    void listar_devolvePaginaOrdenadaComContagemDeUso() throws Exception {
        vincular(inserirCor("ROSA"), 2);
        inserirCor("AZUL");
        inserirCor("CINZA-ESCURO");

        mockMvc.perform(get("/api/v1/cores?pagina=0&tamanho=20")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pagina").value(0))
                .andExpect(jsonPath("$.data.totalElementos").value(3))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("AZUL"))
                .andExpect(jsonPath("$.data.conteudo[0].produtosVinculados").value(0))
                .andExpect(jsonPath("$.data.conteudo[1].nome").value("CINZA-ESCURO"))
                .andExpect(jsonPath("$.data.conteudo[2].nome").value("ROSA"))
                .andExpect(jsonPath("$.data.conteudo[2].produtosVinculados").value(2));
    }

    @Test
    void listar_filtroComTermoCru_achaOCanonico() throws Exception {
        inserirCor("CINZA-ESCURO");
        inserirCor("AZUL");

        mockMvc.perform(get("/api/v1/cores").param("nome", "cinza escuro")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("CINZA-ESCURO"));
    }

    @Test
    void listar_tamanhoForaDoRange_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/cores?tamanho=101")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("tamanho"));
    }

    // ---- CA-5: PUT 409 / 200 no proprio canonico / 200 renomeando / 404 ------------------------

    @Test
    void atualizar_paraCanonicoDeOutraCor_devolve409() throws Exception {
        inserirCor("AZUL");
        Long rosa = inserirCor("ROSA");

        mockMvc.perform(put("/api/v1/cores/" + rosa).header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"azul\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("Já existe uma cor com esse nome."));
    }

    @Test
    void atualizar_proprioCanonicoEDepoisRenomeando_devolve200() throws Exception {
        Long rosa = inserirCor("ROSA");

        mockMvc.perform(put("/api/v1/cores/" + rosa).header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Rosa\",\"hex\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nome").value("ROSA"))
                .andExpect(jsonPath("$.data.hex").doesNotExist());

        mockMvc.perform(put("/api/v1/cores/" + rosa).header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"rosa clara\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nome").value("ROSA-CLARA"));

        assertEquals("ROSA-CLARA", jdbc.queryForObject("SELECT nome FROM cor", String.class));
    }

    @Test
    void atualizar_idInexistente_devolve404() throws Exception {
        mockMvc.perform(put("/api/v1/cores/99").header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"azul\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- CA-6: DELETE 204 sem vinculo · 409 com a contagem real e nada apagado -----------------

    @Test
    void excluir_corSemVinculo_devolve204EApagaALinha() throws Exception {
        Long azul = inserirCor("AZUL");

        mockMvc.perform(delete("/api/v1/cores/" + azul).header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertEquals("", result.getResponse().getContentAsString()));

        assertEquals(0, contarCores());
    }

    @Test
    void excluir_corEmUso_devolve409ComAContagemEPreservaVinculos() throws Exception {
        Long rosa = inserirCor("ROSA");
        vincular(rosa, 3);

        mockMvc.perform(delete("/api/v1/cores/" + rosa).header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("Cor em uso por 3 produto(s) — "
                        + "desvincule dos produtos antes de excluir."));

        assertEquals(1, contarCores());
        assertEquals(3, (int) jdbc.queryForObject(
                "SELECT count(*) FROM produto_cor WHERE cor_id = ?", Integer.class, rosa));
    }

    @Test
    void excluir_idInexistente_devolve404() throws Exception {
        mockMvc.perform(delete("/api/v1/cores/99").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound());
    }

    // ---- CA-7: RBAC (leitura USER+ADMIN, escrita ADMIN) e Swagger ------------------------------

    @Test
    void listarEDetalhar_comUser_devolve200() throws Exception {
        Long azul = inserirCor("AZUL");

        mockMvc.perform(get("/api/v1/cores").header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("AZUL"));
        mockMvc.perform(get("/api/v1/cores/" + azul).header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nome").value("AZUL"));
    }

    @Test
    void escrever_comUser_devolve403NosTresMetodos() throws Exception {
        Long azul = inserirCor("AZUL");
        String body = "{\"nome\":\"rosa\"}";

        mockMvc.perform(post("/api/v1/cores").header(HttpHeaders.AUTHORIZATION, userBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(put("/api/v1/cores/" + azul).header(HttpHeaders.AUTHORIZATION, userBearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/cores/" + azul).header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isForbidden());

        assertEquals(1, contarCores());
    }

    @Test
    void qualquerRota_semToken_devolve401() throws Exception {
        Long azul = inserirCor("AZUL");

        mockMvc.perform(get("/api/v1/cores"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/cores/" + azul)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/cores").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"rosa\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/cores/" + azul).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"rosa\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/cores/" + azul)).andExpect(status().isUnauthorized());
    }

    /** CA-7 / gate item 5: as 5 operacoes de /cores no /v3/api-docs, com summary preenchido. */
    @Test
    void swaggerExpoeRotasDeCores() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/cores'].get.tags[0]").value("cores"))
                .andExpect(jsonPath("$.paths['/api/v1/cores'].get.summary").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/cores'].post.summary").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/cores/{id}'].get.summary").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/cores/{id}'].put.summary").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/cores/{id}'].delete.summary").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/cores'].post.security[0].bearerAuth").exists());
    }

    // ---- Rede de corrida: DataIntegrityViolation mapeada pelo NOME do constraint (armadilha #21) -

    private ResponseEntity<ApiResponse<Object>> traduzir(String constraint, String uri) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRequestURI(uri);
        return corController.handleIntegridade(new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: viola a restricao \"" + constraint + "\"")), http);
    }

    @Test
    void violacaoDeUkCorNome_viraConflitoDeDuplicata() {
        ResponseEntity<ApiResponse<Object>> resposta = traduzir("uk_cor_nome", "/api/v1/cores");

        assertEquals(409, resposta.getStatusCode().value());
        assertNotNull(resposta.getBody());
        assertEquals("CONFLICT", resposta.getBody().error().code());
        assertEquals("Já existe uma cor com esse nome.", resposta.getBody().error().message());
    }

    @Test
    void violacaoDeCkNomeCanonico_vira400NoCampoNomeENao409() {
        ResponseEntity<ApiResponse<Object>> resposta =
                traduzir("ck_cor_nome_canonico", "/api/v1/cores");

        assertEquals(400, resposta.getStatusCode().value()); // 409 mascararia bug como duplicata
        assertNotNull(resposta.getBody());
        assertEquals("VALIDATION_ERROR", resposta.getBody().error().code());
        assertEquals("nome", resposta.getBody().error().details().get(0).field());
    }

    @Test
    void violacaoDeFkPcCor_vira409ComAContagemRecontada() {
        Long rosa = inserirCor("ROSA");
        vincular(rosa, 3);

        ResponseEntity<ApiResponse<Object>> resposta =
                traduzir("fk_pc_cor", "/api/v1/cores/" + rosa);

        assertEquals(409, resposta.getStatusCode().value());
        assertNotNull(resposta.getBody());
        assertEquals("Cor em uso por 3 produto(s) — desvincule dos produtos antes de excluir.",
                resposta.getBody().error().message());
    }
}
