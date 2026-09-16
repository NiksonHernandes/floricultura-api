package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import java.math.BigDecimal;
import java.util.Map;
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
 * Integracao dos <b>valores financeiros</b> do M7 (T-M7-01, CA-4/CA-5/CA-6/CA-53 — SPEC-M7 §3.2)
 * contra um PostgreSQL de DESCARTE (Testcontainers postgres:16), com controller/servico/
 * SecurityConfig/JWT/Flyway V1..V13 reais.
 *
 * <p>Prova: (a) o POST com valores grava e devolve os totais calculados <b>no servidor</b>; (b) o POST
 * sem valores mantem o contrato do M2/M4/M5 palavra por palavra (as 5 colunas ficam {@code NULL});
 * (c) as 8 validacoes do §3.2-c, cada uma com o {@code field} da tabela e com <b>nada gravado</b>
 * (ledger e {@code estoque_atual} conferidos depois da chamada); (d) CA-53 — a linha gravada
 * <b>fecha consigo mesma</b> ({@code total_bruto = round(quantidade x valor_unitario, 2)}) mesmo
 * quando o payload manda 3 casas. Dados ficticios (LGPD): nomes de planta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MovimentacaoValoresApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-valores-api-0123456789abcd");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private Long produtoId;

    @BeforeEach
    void seed() {
        // TRUNCATE: o ledger e imutavel (a trigger barra DELETE de linha).
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        Long adminId = jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES ('Admin', ?, ?, 'ADMIN', true, false) RETURNING id",
                Long.class, "admin-valores@floricultura.local",
                ENCODER.encode(UUID.randomUUID().toString()));
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
        produtoId = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES ('Rosa Vermelha', 'un', 1, 100) RETURNING id", Long.class);
    }

    private String url() {
        return "/api/v1/produtos/" + produtoId + "/movimentacoes";
    }

    private org.springframework.test.web.servlet.ResultActions postar(String corpo) throws Exception {
        return mockMvc.perform(post(url())
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo));
    }

    private Map<String, Object> ultimaLinha() {
        return jdbc.queryForMap(
                "SELECT quantidade, valor_unitario, desconto_tipo, desconto_valor, total_bruto, "
                        + "total_final, estorna_movimentacao_id FROM movimentacao_estoque "
                        + "ORDER BY id DESC LIMIT 1");
    }

    private int contarLancamentos() {
        return jdbc.queryForObject("SELECT count(*) FROM movimentacao_estoque", Integer.class);
    }

    private BigDecimal estoqueAtual() {
        return jdbc.queryForObject(
                "SELECT estoque_atual FROM produto WHERE id = ?", BigDecimal.class, produtoId);
    }

    /** Roda o payload e exige 400 com o field esperado E que NADA tenha sido gravado. */
    private void esperaQuatrocentosSemGravar(String corpo, String field, String message)
            throws Exception {
        int lancamentosAntes = contarLancamentos();
        BigDecimal estoqueAntes = estoqueAtual();

        postar(corpo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value(field))
                .andExpect(jsonPath("$.error.details[0].message").value(message));

        assertEquals(lancamentosAntes, contarLancamentos(), "nada foi gravado no ledger");
        assertEquals(0, estoqueAntes.compareTo(estoqueAtual()), "estoque_atual inalterado");
    }

    // ---- CA-4: o caminho feliz, com os totais calculados no servidor -------------------------

    /** 3 x 10,00 com 15 % => bruto 30,00 e final 25,50, na resposta E nas colunas. */
    @Test
    void postComValoresGravaEDevolveOsTotais() throws Exception {
        postar("{\"tipo\":\"SAIDA\",\"quantidade\":3,\"motivo\":\"Venda balcao\","
                + "\"valorUnitario\":10.00,\"descontoTipo\":\"PERCENTUAL\",\"descontoValor\":15}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.valorUnitario").value(10.00))
                .andExpect(jsonPath("$.data.descontoTipo").value("PERCENTUAL"))
                .andExpect(jsonPath("$.data.descontoValor").value(15.00))
                .andExpect(jsonPath("$.data.totalBruto").value(30.00))
                .andExpect(jsonPath("$.data.totalFinal").value(25.50))
                .andExpect(jsonPath("$.data.estornaMovimentacaoId").doesNotExist());

        Map<String, Object> linha = ultimaLinha();
        assertEquals(new BigDecimal("10.00"), linha.get("valor_unitario"));
        assertEquals(new BigDecimal("30.00"), linha.get("total_bruto"));
        assertEquals(new BigDecimal("25.50"), linha.get("total_final"));
    }

    /** Os totais NUNCA vem do payload (§3.2-a): mandados a mais, sao ignorados. */
    @Test
    void totaisDoPayloadSaoIgnorados() throws Exception {
        postar("{\"tipo\":\"SAIDA\",\"quantidade\":3,\"valorUnitario\":10.00,"
                + "\"totalBruto\":1.00,\"totalFinal\":1.00}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.totalBruto").value(30.00))
                .andExpect(jsonPath("$.data.totalFinal").value(30.00));
    }

    // ---- CA-5: sem valores, o contrato antigo continua palavra por palavra -------------------

    /** Lancamento sem dinheiro (P6): 201 e as 6 colunas novas nulas — nada muda para o M2/M4/M5. */
    @Test
    void postSemValoresMantemOContratoAntigo() throws Exception {
        postar("{\"tipo\":\"ENTRADA\",\"quantidade\":5,\"motivo\":\"Compra\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.quantidadeResultante").value(105))
                .andExpect(jsonPath("$.data.valorUnitario").doesNotExist())
                .andExpect(jsonPath("$.data.descontoTipo").doesNotExist())
                .andExpect(jsonPath("$.data.descontoValor").doesNotExist())
                .andExpect(jsonPath("$.data.totalBruto").doesNotExist())
                .andExpect(jsonPath("$.data.totalFinal").doesNotExist())
                .andExpect(jsonPath("$.data.estornaMovimentacaoId").doesNotExist());

        Map<String, Object> linha = ultimaLinha();
        assertEquals(null, linha.get("valor_unitario"));
        assertEquals(null, linha.get("desconto_tipo"));
        assertEquals(null, linha.get("desconto_valor"));
        assertEquals(null, linha.get("total_bruto"));
        assertEquals(null, linha.get("total_final"));
    }

    // ---- CA-6: as 8 validacoes do §3.2-c, com field e sem escrita -----------------------------

    /** V1: valor unitario negativo (Bean Validation no DTO). */
    @Test
    void v1ValorUnitarioNegativoE400() throws Exception {
        esperaQuatrocentosSemGravar(
                "{\"tipo\":\"SAIDA\",\"quantidade\":3,\"valorUnitario\":-1}",
                "valorUnitario", "Valor unitário deve ser maior ou igual a zero.");
    }

    /** V2 (PA#1): AJUSTE nao aceita dinheiro — nem valor, nem desconto. */
    @Test
    void v2AjusteComValorE400() throws Exception {
        esperaQuatrocentosSemGravar(
                "{\"tipo\":\"AJUSTE\",\"quantidade\":3,\"valorUnitario\":10.00}",
                "valorUnitario", "AJUSTE não aceita valores financeiros.");
        esperaQuatrocentosSemGravar(
                "{\"tipo\":\"AJUSTE\",\"quantidade\":3,\"descontoTipo\":\"VALOR\",\"descontoValor\":2}",
                "valorUnitario", "AJUSTE não aceita valores financeiros.");
    }

    /** V3: desconto sem base de calculo. */
    @Test
    void v3DescontoSemValorUnitarioE400() throws Exception {
        esperaQuatrocentosSemGravar(
                "{\"tipo\":\"SAIDA\",\"quantidade\":3,\"descontoTipo\":\"PERCENTUAL\","
                        + "\"descontoValor\":10}",
                "descontoTipo", "Desconto exige valor unitário.");
    }

    /** V4: o par tipo/valor anda junto — e o field aponta o AUSENTE, nos dois sentidos. */
    @Test
    void v4ParDeDescontoIncompletoE400NoCampoAusente() throws Exception {
        esperaQuatrocentosSemGravar(
                "{\"tipo\":\"SAIDA\",\"quantidade\":3,\"valorUnitario\":10.00,"
                        + "\"descontoTipo\":\"PERCENTUAL\"}",
                "descontoValor", "Informe o tipo e o valor do desconto.");
        esperaQuatrocentosSemGravar(
                "{\"tipo\":\"SAIDA\",\"quantidade\":3,\"valorUnitario\":10.00,\"descontoValor\":5}",
                "descontoTipo", "Informe o tipo e o valor do desconto.");
    }

    /** V5: descontoTipo fora do conjunto (Bean Validation no DTO). */
    @Test
    void v5DescontoTipoInvalidoE400() throws Exception {
        esperaQuatrocentosSemGravar(
                "{\"tipo\":\"SAIDA\",\"quantidade\":3,\"valorUnitario\":10.00,"
                        + "\"descontoTipo\":\"METADE\",\"descontoValor\":5}",
                "descontoTipo", "descontoTipo deve ser um de: PERCENTUAL, VALOR");
    }

    /** V6: desconto negativo (Bean Validation no DTO). */
    @Test
    void v6DescontoNegativoE400() throws Exception {
        esperaQuatrocentosSemGravar(
                "{\"tipo\":\"SAIDA\",\"quantidade\":3,\"valorUnitario\":10.00,"
                        + "\"descontoTipo\":\"VALOR\",\"descontoValor\":-5}",
                "descontoValor", "Desconto deve ser maior ou igual a zero.");
    }

    /** V7: percentual acima de 100 tornaria o total negativo. */
    @Test
    void v7PercentualAcimaDeCemE400() throws Exception {
        esperaQuatrocentosSemGravar(
                "{\"tipo\":\"SAIDA\",\"quantidade\":3,\"valorUnitario\":10.00,"
                        + "\"descontoTipo\":\"PERCENTUAL\",\"descontoValor\":100.01}",
                "descontoValor", "Desconto percentual deve estar entre 0 e 100.");
    }

    /** V8: desconto em reais maior que o bruto — por UM centavo, nao por um numero redondo. */
    @Test
    void v8DescontoEmReaisAcimaDoBrutoE400() throws Exception {
        esperaQuatrocentosSemGravar(
                "{\"tipo\":\"SAIDA\",\"quantidade\":3,\"valorUnitario\":10.00,"
                        + "\"descontoTipo\":\"VALOR\",\"descontoValor\":30.01}",
                "descontoValor", "Desconto não pode exceder o total bruto.");
    }

    // ---- CA-53: a linha gravada fecha consigo mesma (prova end-to-end, por SELECT) ------------

    /**
     * Payload com 3 casas ({@code 10.005}) e quantidade 2: a coluna guarda {@code 10.01} e o bruto e
     * {@code 20.02} — e nao {@code 20.01}, que sairia do valor cru e deixaria a linha inconsistente
     * com a propria coluna, num registro que ninguem pode corrigir depois.
     */
    @Test
    void ca53LinhaGravadaFechaConsigoMesma() throws Exception {
        postar("{\"tipo\":\"SAIDA\",\"quantidade\":2,\"valorUnitario\":10.005}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.valorUnitario").value(10.01))
                .andExpect(jsonPath("$.data.totalBruto").value(20.02))
                .andExpect(jsonPath("$.data.totalFinal").value(20.02));

        Map<String, Object> conferencia = jdbc.queryForMap(
                "SELECT valor_unitario, total_bruto, "
                        + "round(quantidade * valor_unitario, 2) AS bruto_recalculado "
                        + "FROM movimentacao_estoque ORDER BY id DESC LIMIT 1");
        assertEquals(new BigDecimal("10.01"), conferencia.get("valor_unitario"));
        assertEquals(new BigDecimal("20.02"), conferencia.get("total_bruto"));
        assertEquals(conferencia.get("bruto_recalculado"), conferencia.get("total_bruto"));
    }

    /**
     * <b>O e2e que REPROVA</b> (§3.2-b2, CA-53). O caso acima ({@code 10.005}) documenta a regra mas nao
     * a prova: ele fica verde tambem quando o numero passa por {@code double} em algum ponto do
     * caminho, porque o erro de representacao empurra {@code 10.005} para CIMA do empate. Com
     * {@code 8.165} o erro empurra para BAIXO ({@code 8.16499999999999914...}) e a linha gravada sai com
     * {@code 8.16}/{@code 16.32}.
     *
     * <p>Alem de repetir a aritmetica do unitario, este caso tem um papel que so existe aqui: ele
     * atravessa o caminho inteiro — texto JSON, desserializacao do Jackson, servico, JDBC,
     * {@code NUMERIC(14,2)} — e por isso e a prova de que o numero chega ao {@code BigDecimal}
     * <b>sem passar por {@code double}</b> em lugar nenhum. Se passasse, o banco guardaria 8,16.
     */
    @Test
    void ca53ValorDiscriminanteChegaAoBancoSemPassarPorDouble() throws Exception {
        postar("{\"tipo\":\"SAIDA\",\"quantidade\":2,\"valorUnitario\":8.165}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.valorUnitario").value(8.17))
                .andExpect(jsonPath("$.data.totalBruto").value(16.34))
                .andExpect(jsonPath("$.data.totalFinal").value(16.34));

        Map<String, Object> conferencia = jdbc.queryForMap(
                "SELECT valor_unitario, total_bruto, "
                        + "round(quantidade * valor_unitario, 2) AS bruto_recalculado "
                        + "FROM movimentacao_estoque ORDER BY id DESC LIMIT 1");
        assertEquals(new BigDecimal("8.17"), conferencia.get("valor_unitario"));
        assertEquals(new BigDecimal("16.34"), conferencia.get("total_bruto"));
        assertEquals(conferencia.get("bruto_recalculado"), conferencia.get("total_bruto"));
    }
}
