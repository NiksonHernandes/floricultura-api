package com.floricultura.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Anti-burla CA-3 (T-M7-01): a {@code trg_movimentacao_imutavel} ENDURECIDA na V13 passa a cobrir as 6
 * colunas novas de dinheiro/estorno. Integracao contra PostgreSQL de DESCARTE (Testcontainers
 * postgres:16), Flyway V1..V13 na subida.
 *
 * <p><b>O buraco que este arquivo fecha</b> (reproduzido em banco de descarte antes de escrever a
 * migracao): a funcao ENUMERA as colunas que precisam continuar identicas para tolerar o {@code
 * UPDATE} de cascade do hard delete (FC-08). Coluna nova nao enumerada ficava de fora, entao
 * {@code UPDATE ... SET produto_id = NULL, valor_unitario = 999} satisfazia todas as igualdades
 * listadas, caia no {@code RETURN NEW} e <b>alterava dinheiro ja gravado</b> — a trigger devolvia
 * {@code UPDATE 1} sem reclamar.
 *
 * <p>Os 5 casos cobrem os DOIS lados, de proposito: (1) UPDATE arbitrario de valor; (2)(3)(4) as
 * colunas novas alteradas <b>disfarcadas de cascade legitimo</b> -> rejeitadas; (5) o cascade PURO
 * -> PERMITIDO, com os valores preservados. Sem o caso (5), uma trigger que simplesmente rejeitasse
 * tudo passaria nesta suite e quebraria o hard delete LGPD. Complementa {@code
 * MovimentacaoImutavelTest} (V7), que continua verde e NAO e tocado. Dados ficticios (LGPD).
 */
@SpringBootTest
@Testcontainers
class MovimentacaoImutavelValoresTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-imutavel-valores-0123456789ab");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private Long inserirProduto(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    /** Linha do ledger COM dinheiro congelado: 3 x 10,00 com 15 % => bruto 30,00, final 25,50. */
    private Long inserirMovimentacaoComValores(Long produtoId, String produtoNome) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque (produto_id, produto_nome, tipo, quantidade, "
                        + "quantidade_resultante, usuario_nome, valor_unitario, desconto_tipo, "
                        + "desconto_valor, total_bruto, total_final) "
                        + "VALUES (?, ?, 'SAIDA', 3, 7, 'Ana', 10.00, 'PERCENTUAL', 15.00, 30.00, 25.50) "
                        + "RETURNING id",
                Long.class, produtoId, produtoNome);
    }

    private Map<String, Object> valoresDa(Long movId) {
        return jdbc.queryForMap(
                "SELECT produto_id, valor_unitario, desconto_tipo, desconto_valor, total_bruto, "
                        + "total_final, estorna_movimentacao_id FROM movimentacao_estoque WHERE id = ?",
                movId);
    }

    // ----- CA-3: UPDATE arbitrario de dinheiro continua rejeitado -----

    /** Guarda geral (herdada de V1/V4): mexer no valor sem disfarce algum e rejeitado. */
    @Test
    void updateArbitrarioDeValorUnitarioERejeitado() {
        Long produtoId = inserirProduto("Rosa Vermelha");
        Long movId = inserirMovimentacaoComValores(produtoId, "Rosa Vermelha");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE movimentacao_estoque SET valor_unitario = 999.00 WHERE id = ?", movId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");

        assertThat(valoresDa(movId).get("valor_unitario"))
                .isEqualTo(new BigDecimal("10.00"));
    }

    // ----- CA-3: as colunas novas DISFARCADAS de cascade legitimo — o buraco que a V13 fecha -----

    /**
     * O caso que dava o buraco: anular {@code produto_id} (mimetizando o cascade do hard delete) e
     * levar o {@code valor_unitario} de carona. Nenhum CHECK e violado aqui — quem tem de barrar e a
     * trigger. Antes da V13 este UPDATE devolvia "UPDATE 1".
     */
    @Test
    void cascadeComValorUnitarioAlteradoJuntoERejeitado() {
        Long produtoId = inserirProduto("Lirio Branco");
        Long movId = inserirMovimentacaoComValores(produtoId, "Lirio Branco");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE movimentacao_estoque SET produto_id = NULL, valor_unitario = 999.00 WHERE id = ?",
                movId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");

        Map<String, Object> linha = valoresDa(movId);
        assertThat(linha.get("produto_id")).as("nada foi alterado").isEqualTo(produtoId);
        assertThat(linha.get("valor_unitario")).isEqualTo(new BigDecimal("10.00"));
    }

    /** Mesmo disfarce, agora pelo total FINAL (o numero que o relatorio soma). */
    @Test
    void cascadeComTotalFinalAlteradoJuntoERejeitado() {
        Long produtoId = inserirProduto("Orquidea");
        Long movId = inserirMovimentacaoComValores(produtoId, "Orquidea");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE movimentacao_estoque SET produto_id = NULL, total_final = 1.00 WHERE id = ?",
                movId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");

        assertThat(valoresDa(movId).get("total_final")).isEqualTo(new BigDecimal("25.50"));
    }

    /**
     * Mesmo disfarce, agora pelo ponteiro de estorno: forjar "esta linha estorna aquela" a posteriori
     * falsificaria a auditoria do M7 (e o {@code ux_mov_estorno} nao ve UPDATE barrado pela trigger).
     */
    @Test
    void cascadeComEstornaMovimentacaoIdAlteradoJuntoERejeitado() {
        Long produtoId = inserirProduto("Girassol");
        Long alvoId = inserirMovimentacaoComValores(produtoId, "Girassol");
        Long movId = inserirMovimentacaoComValores(produtoId, "Girassol");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE movimentacao_estoque SET produto_id = NULL, estorna_movimentacao_id = ? "
                        + "WHERE id = ?", alvoId, movId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("imutavel");

        assertThat(valoresDa(movId).get("estorna_movimentacao_id")).isNull();
    }

    // ----- CA-3 (nao-regressao): o cascade PURO continua permitido e preserva o dinheiro -----

    /**
     * O outro lado, sem o qual "rejeitar tudo" passaria nesta suite: o hard delete do produto (FC-08)
     * anula {@code produto_id} por {@code ON DELETE SET NULL} e a linha sobrevive com os snapshots
     * <b>e com os 5 valores</b> intactos.
     */
    @Test
    void cascadePuroPreservaValores() {
        Long produtoId = inserirProduto("Margarida");
        Long movId = inserirMovimentacaoComValores(produtoId, "Margarida");

        assertThatCode(() -> jdbc.update("DELETE FROM produto WHERE id = ?", produtoId))
                .doesNotThrowAnyException();

        Map<String, Object> linha = valoresDa(movId);
        assertThat(linha.get("produto_id")).as("cascade aplicado").isNull();
        assertThat(linha.get("valor_unitario")).isEqualTo(new BigDecimal("10.00"));
        assertThat(linha.get("desconto_tipo")).isEqualTo("PERCENTUAL");
        assertThat(linha.get("desconto_valor")).isEqualTo(new BigDecimal("15.00"));
        assertThat(linha.get("total_bruto")).isEqualTo(new BigDecimal("30.00"));
        assertThat(linha.get("total_final")).isEqualTo(new BigDecimal("25.50"));
    }
}
