package com.floricultura.api.web;

import static org.hamcrest.Matchers.nullValue;
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
 * Integracao dos atributos <b>multivalorados</b> do produto (SPEC-M6 §3.3/§3.4/§3.5, T-M6-02b) contra um
 * PostgreSQL de DESCARTE (Testcontainers postgres:16). Cobre <b>CA-11</b> ({@code corIds}: dedup,
 * ordenacao por nome, id inexistente → 400 com o vinculo anterior intacto, {@code []} limpa, omitido
 * preserva), <b>CA-12</b> (mesma matriz para {@code necessidadeLuz}), <b>CA-13</b> (as 2 colecoes vem
 * <b>{@code null}</b> na LISTA e preenchidas no DETALHE) e <b>CA-16</b> (hard delete limpa as duas
 * juncoes e o catalogo de cores <b>permanece</b>).
 *
 * <p><b>Arquivo novo, e nao casos acrescentados ao {@code ProdutoAtributosApiTest}</b> (precedente da
 * T-M6-08d, §6): mantem "so adicao" trivialmente verificavel no diff de {@code src/test} e deixa a
 * rubrica desta task auto-contida — o javadoc do arquivo da 02a, que declara as colecoes fora do seu
 * escopo, continua factualmente verdadeiro.
 *
 * <p><b>Por que {@code .value(nullValue())} e nao {@code doesNotExist()} nas colecoes da lista:</b> o
 * projeto nao usa {@code NON_NULL}, e o {@code doesNotExist()} do Spring <b>passa</b> quando o valor e
 * {@code null} — nao distinguiria "veio null como o contrato manda" de "o campo sumiu do contrato". Na
 * lista o CA-13 exige o {@code null} explicito, entao a assercao tem que ser sobre o VALOR.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoMultivaloradosApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-multivalorados-0123456789");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private Long corVermelho;
    private Long corAzul;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM produto_cor");
        jdbc.update("DELETE FROM produto_necessidade_luz");
        jdbc.update("DELETE FROM cor");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        Long adminId = jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES ('Admin Cores', 'admin-cores@floricultura.local', ?, 'ADMIN', "
                        + "true, false) RETURNING id",
                Long.class, ENCODER.encode(UUID.randomUUID().toString()));
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
        corVermelho = inserirCor("VERMELHO", "#C4326B");
        corAzul = inserirCor("AZUL", null);
    }

    // ---- CA-8/CA-11/CA-12: produto sem colecoes nasce com [] (nunca null no detalhe) -----------

    @Test
    void criar_semColecoes_nasceComAsDuasListasVazias() throws Exception {
        mockMvc.perform(postProduto("""
                        {"nome":"Samambaia","unidadeMedida":"un","estoqueMinimo":1}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cores").isArray())
                .andExpect(jsonPath("$.data.cores").isEmpty())
                .andExpect(jsonPath("$.data.necessidadeLuz").isArray())
                .andExpect(jsonPath("$.data.necessidadeLuz").isEmpty());
    }

    // ---- CA-11: matriz de corIds ---------------------------------------------------------------

    @Test
    void atualizar_corIdsComRepetido_dedupEDevolveOrdenadoPorNome() throws Exception {
        Long id = criarProduto("Rosa");

        mockMvc.perform(putProduto(id, corpoCom("\"corIds\":[%d,%d,%d]"
                        .formatted(corVermelho, corAzul, corVermelho))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cores.length()").value(2))
                .andExpect(jsonPath("$.data.cores[0].nome").value("AZUL"))   // ORDER BY nome ASC
                .andExpect(jsonPath("$.data.cores[0].hex").value(nullValue()))
                .andExpect(jsonPath("$.data.cores[1].nome").value("VERMELHO"))
                .andExpect(jsonPath("$.data.cores[1].hex").value("#C4326B"));

        assertEquals(2, contar("produto_cor", id));
    }

    @Test
    void atualizar_corIdInexistente_devolve400ComFieldCorIds_eMantemOVinculoAnterior()
            throws Exception {
        Long id = criarProduto("Orquídea");
        vincularCor(id, corAzul);

        mockMvc.perform(putProduto(id, corpoCom("\"corIds\":[%d,777]".formatted(corVermelho))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("corIds"))
                .andExpect(jsonPath("$.error.details[0].message").value("Cor inexistente: 777."));

        // R10: validado ANTES de qualquer escrita — o DELETE do replace-set nem chegou a rodar.
        assertEquals(1, contar("produto_cor", id));
        assertEquals(corAzul, jdbc.queryForObject(
                "SELECT cor_id FROM produto_cor WHERE produto_id = ?", Long.class, id));
    }

    @Test
    void atualizar_corIdsVazio_limpa_eOmitido_preserva() throws Exception {
        Long id = criarProduto("Girassol");
        vincularCor(id, corAzul);

        mockMvc.perform(putProduto(id, corpoCom("\"corIds\":[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cores").isEmpty());
        assertEquals(0, contar("produto_cor", id));

        vincularCor(id, corVermelho);
        mockMvc.perform(putProduto(id, corpoCom("\"descricao\":\"sem tocar nas cores\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cores.length()").value(1))
                .andExpect(jsonPath("$.data.cores[0].nome").value("VERMELHO"));
        assertEquals(1, contar("produto_cor", id));
    }

    // ---- CA-12: matriz de necessidadeLuz -------------------------------------------------------

    @Test
    void atualizar_necessidadeLuzComRepetido_dedupEDevolveOrdenado() throws Exception {
        Long id = criarProduto("Antúrio");

        mockMvc.perform(putProduto(id,
                        corpoCom("\"necessidadeLuz\":[\"SOMBRA\",\"MEIA_SOMBRA\",\"SOMBRA\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.necessidadeLuz.length()").value(2))
                .andExpect(jsonPath("$.data.necessidadeLuz[0]").value("MEIA_SOMBRA")) // ORDER BY luz
                .andExpect(jsonPath("$.data.necessidadeLuz[1]").value("SOMBRA"));

        assertEquals(2, contar("produto_necessidade_luz", id));
    }

    @Test
    void criar_necessidadeLuzForaDoConjunto_devolve400ComOField_eNaoPersiste() throws Exception {
        mockMvc.perform(postProduto("""
                        {"nome":"Lunária","unidadeMedida":"un","estoqueMinimo":1,
                         "necessidadeLuz":["LUA"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("necessidadeLuz"));

        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT count(*) FROM produto", Integer.class));
    }

    @Test
    void atualizar_necessidadeLuzVazia_limpa_eOmitida_preserva() throws Exception {
        Long id = criarProduto("Begônia");
        jdbc.update("INSERT INTO produto_necessidade_luz (produto_id, luz) VALUES (?, 'SOL_PLENO')",
                id);

        mockMvc.perform(putProduto(id, corpoCom("\"necessidadeLuz\":[]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.necessidadeLuz").isEmpty());
        assertEquals(0, contar("produto_necessidade_luz", id));

        jdbc.update("INSERT INTO produto_necessidade_luz (produto_id, luz) VALUES (?, 'SOMBRA')", id);
        mockMvc.perform(putProduto(id, corpoCom("\"descricao\":\"sem tocar na luz\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.necessidadeLuz[0]").value("SOMBRA"));
        assertEquals(1, contar("produto_necessidade_luz", id));
    }

    // ---- CA-13: colecoes NULL na lista, preenchidas no detalhe --------------------------------

    /**
     * O {@code null} da lista e o contrato (AD-SQ-38/AD-SQ-44), nao um acaso: e por isso que a assercao
     * e {@code .value(nullValue())}. Com {@code doesNotExist()} este caso passaria tanto com o contrato
     * cumprido quanto com o campo removido do JSON — nao provaria nada.
     */
    @Test
    void lista_trazAsDuasColecoesNulas_eDetalheAsTrazPreenchidas() throws Exception {
        Long id = criarProduto("Espada-de-são-jorge");
        vincularCor(id, corVermelho);
        jdbc.update("INSERT INTO produto_necessidade_luz (produto_id, luz) VALUES (?, 'SOL_PLENO')",
                id);

        mockMvc.perform(get("/api/v1/produtos").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Espada-de-são-jorge"))
                .andExpect(jsonPath("$.data.conteudo[0].cores").value(nullValue()))
                .andExpect(jsonPath("$.data.conteudo[0].necessidadeLuz").value(nullValue()))
                // Os escalares, esses, continuam na lista (colunas da propria linha — custo zero).
                .andExpect(jsonPath("$.data.conteudo[0].estoqueBaixo").exists());

        mockMvc.perform(get("/api/v1/produtos/" + id).header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cores[0].id").value(corVermelho))
                .andExpect(jsonPath("$.data.cores[0].nome").value("VERMELHO"))
                .andExpect(jsonPath("$.data.cores[0].hex").value("#C4326B"))
                .andExpect(jsonPath("$.data.necessidadeLuz[0]").value("SOL_PLENO"));
    }

    // ---- CA-16: hard delete limpa as juncoes e preserva o catalogo ----------------------------

    @Test
    void deletar_produtoComCoresELuzes_limpaAsJuncoes_eMantemOCatalogoDeCores() throws Exception {
        Long id = criarProduto("Costela-de-adão");
        vincularCor(id, corVermelho);
        vincularCor(id, corAzul);
        jdbc.update("INSERT INTO produto_necessidade_luz (produto_id, luz) VALUES (?, 'SOMBRA')", id);
        jdbc.update("INSERT INTO produto_necessidade_luz (produto_id, luz) VALUES (?, 'MEIA_SOMBRA')",
                id);

        mockMvc.perform(delete("/api/v1/produtos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        assertEquals(0, contar("produto_cor", id));
        assertEquals(0, contar("produto_necessidade_luz", id));
        // O catalogo NAO e afetado (FC-08/R6): o CASCADE so vale do lado do produto.
        assertEquals(2, (int) jdbc.queryForObject("SELECT count(*) FROM cor", Integer.class));
    }

    // ---- Swagger (exigencia do dono para todo contrato novo) ----------------------------------

    @Test
    void apiDocs_documentaOsMultivaloradosNoContratoDeProduto() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CriarProdutoRequest.properties.corIds")
                        .exists())
                .andExpect(jsonPath(
                        "$.components.schemas.AtualizarProdutoRequest.properties.necessidadeLuz")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ProdutoResponse.properties.cores").exists())
                .andExpect(jsonPath("$.paths['/api/v1/produtos'].post.summary")
                        .value(org.hamcrest.Matchers.containsString("corIds")));
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

    /** Corpo de PUT valido com os campos obrigatorios + o trecho variavel da vez. */
    private String corpoCom(String trecho) {
        return "{\"nome\":\"Produto\",\"unidadeMedida\":\"un\",\"estoqueMinimo\":1," + trecho + "}";
    }

    private Long criarProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual, ativo) "
                        + "VALUES (?, 'un', 1, 0, true) RETURNING id", Long.class, nome);
    }

    private Long inserirCor(String nome, String hex) {
        return jdbc.queryForObject(
                "INSERT INTO cor (nome, hex) VALUES (?, ?) RETURNING id", Long.class, nome, hex);
    }

    private void vincularCor(Long produtoId, Long corId) {
        jdbc.update("INSERT INTO produto_cor (produto_id, cor_id) VALUES (?, ?)", produtoId, corId);
    }

    private int contar(String tabela, Long produtoId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + tabela + " WHERE produto_id = ?", Integer.class, produtoId);
    }
}
