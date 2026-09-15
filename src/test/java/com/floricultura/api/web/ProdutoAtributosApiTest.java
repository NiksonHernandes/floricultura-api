package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Integracao dos <b>atributos botanicos escalares</b> do produto (SPEC-M6 §3.3/§3.4, T-M6-02a) contra
 * um PostgreSQL de DESCARTE (Testcontainers postgres:16). Cobre <b>CA-8</b> (opcionalidade total +
 * produto pre-V12 legivel/editavel), <b>CA-9</b> (enum fora do conjunto → 400 com o {@code field}),
 * <b>CA-10</b> (matriz altura×caracteristica ponta a ponta — 400, nunca 500 — e faixa 1..10000),
 * <b>CA-13</b> (os 3 escalares presentes na lista <b>e</b> no detalhe) e <b>CA-15</b> (a vitrine do
 * M4.1 hidrata as colunas novas sob query nativa, sem bytea).
 *
 * <p>As colecoes {@code necessidadeLuz}/{@code cores} sao da T-M6-02b e nao aparecem aqui.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoAtributosApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-atributos-0123456789abc");
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
        Long adminId = jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES ('Admin Atributos', 'admin-atr@floricultura.local', ?, 'ADMIN', "
                        + "true, false) RETURNING id",
                Long.class, ENCODER.encode(UUID.randomUUID().toString()));
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
    }

    // ---- CA-8: os 5 campos sao opcionais; produto pre-V12 segue legivel e editavel -------------

    @Test
    void criar_semNenhumAtributo_nasceComOsTresEscalaresNulos() throws Exception {
        mockMvc.perform(postProduto("""
                        {"nome":"Samambaia","unidadeMedida":"un","estoqueMinimo":1}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.caracteristica").doesNotExist())
                .andExpect(jsonPath("$.data.alturaCm").doesNotExist())
                .andExpect(jsonPath("$.data.toxicidade").doesNotExist());
    }

    @Test
    void produtoAnteriorAV12_continuaSendoLidoEEditado() throws Exception {
        // Linha nascida sem nenhuma coluna do M6 (o catalogo existente, P12 — sem backfill).
        Long id = inserirProdutoCru("Cravo Antigo", null, null, null);

        mockMvc.perform(get("/api/v1/produtos/" + id).header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caracteristica").doesNotExist());

        mockMvc.perform(putProduto(id, """
                        {"nome":"Cravo Antigo","unidadeMedida":"un","estoqueMinimo":2,
                         "caracteristica":"ADULTA","alturaCm":45,"toxicidade":"NAO_TOXICA"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caracteristica").value("ADULTA"))
                .andExpect(jsonPath("$.data.alturaCm").value(45));
    }

    // ---- CA-9: enum fora do conjunto -> 400 com o field, nada persiste -------------------------

    @Test
    void criar_caracteristicaForaDoEnum_devolve400ComFieldCaracteristica() throws Exception {
        mockMvc.perform(postProduto("""
                        {"nome":"Broto","unidadeMedida":"un","estoqueMinimo":1,
                         "caracteristica":"BROTO"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("caracteristica"));
        assertEquals(0, contarProdutos());
    }

    @Test
    void criar_toxicidadeForaDoEnum_devolve400ComFieldToxicidade() throws Exception {
        mockMvc.perform(postProduto("""
                        {"nome":"Talvez","unidadeMedida":"un","estoqueMinimo":1,
                         "toxicidade":"TALVEZ"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("toxicidade"));
        assertEquals(0, contarProdutos());
    }

    // ---- CA-10: regra cruzada da altura e faixa 1..10000 --------------------------------------

    @Test
    void criar_mudaComAltura_devolve400ComFieldAlturaCm() throws Exception {
        mockMvc.perform(postProduto("""
                        {"nome":"Muda de Ipe","unidadeMedida":"un","estoqueMinimo":1,
                         "caracteristica":"MUDA","alturaCm":30}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("alturaCm"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("A altura só pode ser informada quando a característica for "
                                + "JOVEM ou ADULTA."));
        assertEquals(0, contarProdutos());
    }

    /**
     * Caso da <b>AD-SQ-89</b>: altura SEM caracteristica. E o que a forma ingenua do CHECK deixava
     * passar — e, se o servico nao barrasse, o CHECK NULL-safe da V12 devolveria 500 em vez de 400.
     */
    @Test
    void criar_alturaSemCaracteristica_devolve400ENaoPersiste() throws Exception {
        mockMvc.perform(postProduto("""
                        {"nome":"Sem Porte","unidadeMedida":"un","estoqueMinimo":1,"alturaCm":30}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("alturaCm"));
        assertEquals(0, contarProdutos());
    }

    @Test
    void atualizar_alturaSemCaracteristica_devolve400ENaoAlteraALinha() throws Exception {
        Long id = inserirProdutoCru("Jovem Ipe", "JOVEM", 80, "NAO_TOXICA");

        mockMvc.perform(putProduto(id, """
                        {"nome":"Jovem Ipe","unidadeMedida":"un","estoqueMinimo":1,"alturaCm":90}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("alturaCm"));

        assertEquals("JOVEM", stringDoProduto(id, "caracteristica"));
        assertEquals(80, jdbc.queryForObject(
                "SELECT altura_cm FROM produto WHERE id = ?", Integer.class, id));
    }

    /** O caso do SDD: trocar so a caracteristica para MUDA numa linha que ja tem altura. */
    @Test
    void atualizar_paraMudaSemLimparAltura_devolve400_masLimpandoAceita() throws Exception {
        Long id = inserirProdutoCru("Ipe", "JOVEM", 80, null);

        mockMvc.perform(putProduto(id, """
                        {"nome":"Ipe","unidadeMedida":"un","estoqueMinimo":1,
                         "caracteristica":"MUDA","alturaCm":80}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("alturaCm"));

        mockMvc.perform(putProduto(id, """
                        {"nome":"Ipe","unidadeMedida":"un","estoqueMinimo":1,
                         "caracteristica":"MUDA"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caracteristica").value("MUDA"))
                .andExpect(jsonPath("$.data.alturaCm").doesNotExist());
        assertNull(jdbc.queryForObject(
                "SELECT altura_cm FROM produto WHERE id = ?", Integer.class, id));
    }

    @Test
    void criar_alturaForaDaFaixa_devolve400() throws Exception {
        for (String altura : new String[] {"0", "10001"}) {
            mockMvc.perform(postProduto("""
                            {"nome":"Fora da faixa","unidadeMedida":"un","estoqueMinimo":1,
                             "caracteristica":"ADULTA","alturaCm":%s}""".formatted(altura)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details[0].field").value("alturaCm"))
                    .andExpect(jsonPath("$.error.details[0].message")
                            .value("A altura deve estar entre 1 e 10000 cm."));
        }
        assertEquals(0, contarProdutos());
    }

    @Test
    void criar_adultaCom120_persisteAlturaEmCentimetros() throws Exception {
        String corpo = mockMvc.perform(postProduto("""
                        {"nome":"Costela-de-adão","unidadeMedida":"un","estoqueMinimo":3,
                         "caracteristica":"ADULTA","alturaCm":120,"toxicidade":"TOXICA"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.alturaCm").value(120))
                .andExpect(jsonPath("$.data.toxicidade").value("TOXICA"))
                .andReturn().getResponse().getContentAsString();

        Long id = com.jayway.jsonpath.JsonPath.parse(corpo).read("$.data.id", Long.class);
        assertEquals(120, jdbc.queryForObject(
                "SELECT altura_cm FROM produto WHERE id = ?", Integer.class, id));
    }

    // ---- CA-13: escalares na LISTA e no DETALHE ----------------------------------------------

    @Test
    void lista_eDetalhe_trazemOsTresEscalares() throws Exception {
        Long id = inserirProdutoCru("Espada-de-são-jorge", "ADULTA", 90, "TOXICA");

        // Lista: colunas da propria linha, custo zero (as colecoes do 02b e que ficam null).
        mockMvc.perform(get("/api/v1/produtos").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo[0].caracteristica").value("ADULTA"))
                .andExpect(jsonPath("$.data.conteudo[0].alturaCm").value(90))
                .andExpect(jsonPath("$.data.conteudo[0].toxicidade").value("TOXICA"));

        mockMvc.perform(get("/api/v1/produtos/" + id).header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caracteristica").value("ADULTA"))
                .andExpect(jsonPath("$.data.alturaCm").value(90))
                .andExpect(jsonPath("$.data.toxicidade").value("TOXICA"));
    }

    // ---- CA-15: a vitrine do M4.1 (query NATIVA) continua de pe -------------------------------

    /**
     * Anti-regressao da armadilha §12 #3: a projecao de {@code buscarPorEvento} enumera colunas para
     * excluir o {@code bytea}; ao mapear os 3 escalares na @Entity, a hidratacao sob query nativa passa
     * a exigi-los no {@code ResultSet}. Sem o fix, este caso falha com erro de coluna ausente.
     *
     * <p>O produto tem imagem no banco de proposito: a vitrine devolve {@code temImagem:true} (metadado
     * leve) e <b>nenhum</b> campo de binario — se alguem "resolvesse" a hidratacao com {@code p.*}, o
     * bytea voltaria as leituras de lista (AD-SQ-38/AD-SQ-50).
     */
    @Test
    void vitrine_hidrataOsEscalaresSobQueryNativa_semBytea() throws Exception {
        Long id = inserirProdutoCru("Lírio da Paz", "ADULTA", 60, "TOXICA");
        jdbc.update("UPDATE produto SET imagem = ?, imagem_content_type = 'image/webp', "
                + "imagem_filename = 'lirio.webp' WHERE id = ?", new byte[] {1, 2, 3}, id);
        Long eventoId = jdbc.queryForObject(
                "INSERT INTO evento (nome, data_inicio, tipo, repete_todo_ano) "
                        + "VALUES ('Dia das Mães', '2026-05-10'::date, 'COMEMORATIVA', false) "
                        + "RETURNING id", Long.class);
        jdbc.update("INSERT INTO evento_produto (evento_id, produto_id) VALUES (?, ?)", eventoId, id);

        mockMvc.perform(get("/api/v1/eventos/" + eventoId + "/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].caracteristica").value("ADULTA"))
                .andExpect(jsonPath("$.data.conteudo[0].alturaCm").value(60))
                .andExpect(jsonPath("$.data.conteudo[0].toxicidade").value("TOXICA"))
                .andExpect(jsonPath("$.data.conteudo[0].sazonal").value(true))
                .andExpect(jsonPath("$.data.conteudo[0].temImagem").value(true))
                .andExpect(jsonPath("$.data.conteudo[0].imagem").doesNotExist())
                .andExpect(jsonPath("$.data.conteudo[0].eventoIds").doesNotExist());
    }

    // ---- Swagger (exigencia do dono para toda rota/contrato novo) -----------------------------

    @Test
    void apiDocs_documentaOsAtributosBotanicosNoContratoDeProduto() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CriarProdutoRequest.properties.caracteristica")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.CriarProdutoRequest.properties.alturaCm")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.AtualizarProdutoRequest.properties.toxicidade")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ProdutoResponse.properties.alturaCm").exists())
                .andExpect(jsonPath("$.paths['/api/v1/produtos'].post.summary")
                        .value(org.hamcrest.Matchers.containsString("botanicos")));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.RequestBuilder postProduto(String corpo) {
        return post("/api/v1/produtos").header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON).content(corpo);
    }

    private org.springframework.test.web.servlet.RequestBuilder putProduto(Long id, String corpo) {
        return put("/api/v1/produtos/" + id).header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON).content(corpo);
    }

    /** Insere a linha direto no banco (simula catalogo pre-existente), sem passar pela API. */
    private Long inserirProdutoCru(
            String nome, String caracteristica, Integer alturaCm, String toxicidade) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual, ativo, "
                        + "caracteristica, altura_cm, toxicidade) "
                        + "VALUES (?, 'un', 1, 0, true, ?, ?, ?) RETURNING id",
                Long.class, nome, caracteristica, alturaCm, toxicidade);
    }

    private int contarProdutos() {
        return jdbc.queryForObject("SELECT count(*) FROM produto", Integer.class);
    }

    private String stringDoProduto(Long id, String coluna) {
        return jdbc.queryForObject(
                "SELECT " + coluna + " FROM produto WHERE id = ?", String.class, id);
    }
}
