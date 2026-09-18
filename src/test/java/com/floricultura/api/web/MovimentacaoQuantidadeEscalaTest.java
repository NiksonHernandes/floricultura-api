package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.CalculoFinanceiro;
import com.floricultura.api.service.JwtService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Passo 0 da <b>quantidade</b> (decisao do dono, 2026-09-18) — o campo que tinha ficado de fora do
 * §3.2-b1, achado pelo reviewer do M7.
 *
 * <p><b>O defeito, em uma frase:</b> a conta multiplicava a quantidade <b>crua</b> e a coluna
 * {@code NUMERIC(14,3)} guardava a <b>arredondada</b>, entao a linha <b>nao fechava consigo mesma</b> —
 * e linha do ledger e <b>imutavel</b>, ou seja, quem reconferisse a multiplicacao acharia outro total
 * e nao teria como corrigir. E exatamente o defeito que o javadoc do {@code CalculoFinanceiro} ja
 * argumentava ter evitado no valor unitario.
 *
 * <p><b>O par de teste tem de DISCRIMINAR (AD-SQ-164).</b> {@code quantidade = 1.5} nao provaria nada:
 * crua e normalizada sao o mesmo numero, e o caso ficaria verde com o defeito presente. O par usado
 * aqui e {@code 1.0005 x 1000.00}: o certo e <b>1001.00</b> e o que saia antes era <b>1000.50</b>.
 *
 * <p><b>Arquivo NOVO:</b> os 3 arquivos de teste desta task ja estao rastreados e o hook os protege
 * ate do autor (§12 #27). Nenhuma liberacao foi pedida — adicao pura.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MovimentacaoQuantidadeEscalaTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-qtd-escala-0123456789");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String admin;
    private Long rosa;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        admin = "Bearer " + jwtService.gerarToken(
                usuario("Ana Admin", "admin-qtd@floricultura.local", "ADMIN"));
        rosa = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES ('Rosa Vermelha', 'un', 1, 0) RETURNING id", Long.class);
    }

    // ---- A unidade: o par que discrimina ------------------------------------------------------

    /**
     * O par discriminante na classe pura: {@code 1.0005} normaliza para {@code 1.001} <b>antes</b> de
     * multiplicar. Sem o passo 0 o resultado seria {@code 1000.50} — meio real de diferenca numa linha
     * que ninguem pode corrigir depois.
     */
    @Test
    @DisplayName("1,0005 x 1.000,00 da 1001,00 (e 1000,50 e o numero errado que saia antes)")
    void quantidadeDeQuatroCasasEntraNormalizadaNaConta() {
        BigDecimal bruto = CalculoFinanceiro.calcular(
                new BigDecimal("1.0005"), new BigDecimal("1000.00"), null, null).totalBruto();

        assertThat(bruto).isEqualByComparingTo(new BigDecimal("1001.00"));
    }

    /**
     * O outro lado, sem o qual a normalizacao poderia estar "consertando" o que ja estava certo:
     * quantidade com <b>3 casas ou menos</b> atravessa <b>intacta</b>.
     */
    @Test
    @DisplayName("quantidade de ate 3 casas nao muda — a normalizacao nao mexe no que ja estava certo")
    void quantidadeDentroDaEscalaAtravessaIntacta() {
        assertThat(CalculoFinanceiro.normalizarQuantidade(new BigDecimal("1.5")))
                .isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(CalculoFinanceiro.calcular(
                new BigDecimal("1.001"), new BigDecimal("1000.00"), null, null).totalBruto())
                .isEqualByComparingTo(new BigDecimal("1001.00"));
        assertThat(CalculoFinanceiro.calcular(
                new BigDecimal("3"), new BigDecimal("10.00"), null, null).totalBruto())
                .isEqualByComparingTo(new BigDecimal("30.00"));
    }

    // ---- O e2e: a linha gravada fecha consigo mesma -------------------------------------------

    /**
     * A identidade que importa, conferida <b>na linha gravada</b> (mesmo principio do BUG-005 no
     * papel): {@code round(quantidade x valor_unitario, 2) = total_bruto}, lido por {@code SELECT}
     * depois do POST — nao recalculado pelo servico.
     */
    @Test
    @DisplayName("a linha gravada fecha consigo mesma: round(quantidade x unitario, 2) = total_bruto")
    void linhaGravadaFechaConsigoMesma() throws Exception {
        mockMvc.perform(post("/api/v1/produtos/" + rosa + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"ENTRADA\",\"quantidade\":1.0005,"
                                + "\"valorUnitario\":1000.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.quantidade").value(1.001))
                .andExpect(jsonPath("$.data.totalBruto").value(1001.00));

        Map<String, Object> linha = jdbc.queryForMap(
                "SELECT quantidade, valor_unitario, total_bruto, total_final "
                        + "FROM movimentacao_estoque");
        BigDecimal quantidade = (BigDecimal) linha.get("quantidade");
        BigDecimal unitario = (BigDecimal) linha.get("valor_unitario");
        BigDecimal bruto = (BigDecimal) linha.get("total_bruto");

        assertThat(quantidade).isEqualByComparingTo(new BigDecimal("1.001"));
        assertThat(bruto)
                .as("a linha e IMUTAVEL: se ela nao fecha consigo mesma, nao ha conserto depois")
                .isEqualByComparingTo(quantidade.multiply(unitario).setScale(2, RoundingMode.HALF_UP));
        assertThat(bruto).isEqualByComparingTo(new BigDecimal("1001.00"));
    }

    /**
     * E o estoque anda pelo <b>mesmo</b> numero que a linha declara — a quantidade normalizada vale
     * para as duas trilhas (dinheiro e estoque), nao so para a do dinheiro.
     */
    @Test
    @DisplayName("o estoque anda exatamente a quantidade que a linha gravou")
    void estoqueAndaPelaQuantidadeNormalizada() throws Exception {
        mockMvc.perform(post("/api/v1/produtos/" + rosa + "/movimentacoes")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"ENTRADA\",\"quantidade\":1.0005,"
                                + "\"valorUnitario\":1000.00}"))
                .andExpect(status().isCreated());

        BigDecimal estoque = jdbc.queryForObject(
                "SELECT estoque_atual FROM produto WHERE id = ?", BigDecimal.class, rosa);
        BigDecimal daLinha = jdbc.queryForObject(
                "SELECT quantidade FROM movimentacao_estoque", BigDecimal.class);

        assertThat(estoque).isEqualByComparingTo(daLinha);
        assertThat(estoque).isEqualByComparingTo(new BigDecimal("1.001"));
    }

    private Long usuario(String nome, String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, nome, email, UUID.randomUUID().toString(), role);
    }
}
