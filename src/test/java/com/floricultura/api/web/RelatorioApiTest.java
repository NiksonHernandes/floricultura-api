package com.floricultura.api.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Relatorio agregado do M7 (T-M7-04, SPEC-M7 §3.7 — CA-20..CA-25, CA-54, CA-55) contra um PostgreSQL
 * de DESCARTE (Testcontainers postgres:16).
 *
 * <p><b>Semeadura por {@code INSERT} direto, nao pela API</b> (mesma razao de prova do
 * {@code MovimentacaoFiltroApiTest}): {@code criado_em} tem {@code DEFAULT now()} e e
 * {@code insertable=false}, entao pela API nao ha como cravar o dia — e o dia e justamente o que
 * separa um balde do outro e o "dentro" do "fora" no par estornado. O par de estorno tambem e semeado
 * a mao <b>de proposito</b>: acoplar este teste ao {@code POST .../estorno} da T-M7-02 tornaria a
 * agregacao refem de um caminho de escrita alheio.
 *
 * <p><b>Dinheiro se assere com {@code double}</b> ({@code value(3500.0)}): o JsonPath desserializa
 * JSON number como {@code Double} e {@code value(new BigDecimal("3500.00"))} <b>nunca</b> casa
 * (§12 #21).
 *
 * <p><b>A identidade {@code somados + excluidos = total do recorte}</b> (§10 #10f) e conferida nos
 * tres casos de par estornado contra um {@code count} <b>independente</b>, feito por JDBC — e o que
 * impede o contador de virar uma consulta escrita a parte que diria "2" tambem no straddle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RelatorioApiTest {

    private static final String BASE = "/api/v1/relatorios/movimentacoes";

    private static final String SETEMBRO = "?de=2026-09-01&ate=2026-09-30";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-relatorio-0123456789ab");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String admin;
    private String user;
    private Long rosa;
    private Long fornecedor;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM fornecedor");
        jdbc.update("DELETE FROM usuario");
        admin = "Bearer " + jwtService.gerarToken(
                usuario("Ana Admin", "admin-rel@floricultura.local", "ADMIN"));
        user = "Bearer " + jwtService.gerarToken(
                usuario("Beto User", "user-rel@floricultura.local", "USER"));
        rosa = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, estoque_minimo, estoque_atual) "
                        + "VALUES ('Rosa Vermelha', 'un', 1, 0) RETURNING id", Long.class);
        fornecedor = jdbc.queryForObject(
                "INSERT INTO fornecedor (nome) VALUES ('Sitio Boa Flor') RETURNING id", Long.class);
    }

    // ---- CA-20: a agregacao ------------------------------------------------------------------

    /**
     * CA-20: resumo por tipo + {@code resultadoValor = saidas.valor − entradas.valor}, com a serie de
     * um unico balde espelhando o resumo. A ENTRADA <b>sem dinheiro</b> (5 colunas {@code NULL}, P6)
     * entra na contagem e na quantidade mas <b>nao contamina</b> o valor — e o {@code coalesce} do
     * §3.7-a sendo provado, nao afirmado.
     */
    @Test
    void agregacaoMensalSomaEntradasSaidasEResultado() throws Exception {
        comValor("ENTRADA", "2026-09-05 10:00", "10", "350.00", "3500.00", null);
        comValor("SAIDA", "2026-09-12 09:00", "5", "940.00", "4700.00", null);
        semValor("ENTRADA", "2026-09-18 11:00", "2");
        semValor("AJUSTE", "2026-09-20 08:00", "3");

        chamar(SETEMBRO, admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.de").value("2026-09-01"))
                .andExpect(jsonPath("$.data.ate").value("2026-09-30"))
                .andExpect(jsonPath("$.data.granularidade").value("MES"))
                .andExpect(jsonPath("$.data.resumo.entradas.lancamentos").value(2))
                .andExpect(jsonPath("$.data.resumo.entradas.quantidade").value(12.0))
                .andExpect(jsonPath("$.data.resumo.entradas.valor").value(3500.0))
                .andExpect(jsonPath("$.data.resumo.saidas.lancamentos").value(1))
                .andExpect(jsonPath("$.data.resumo.saidas.quantidade").value(5.0))
                .andExpect(jsonPath("$.data.resumo.saidas.valor").value(4700.0))
                .andExpect(jsonPath("$.data.resumo.ajustes.lancamentos").value(1))
                .andExpect(jsonPath("$.data.resumo.ajustes.quantidade").value(3.0))
                .andExpect(jsonPath("$.data.resumo.ajustes.valor").value(0.0))
                // 4700.00 − 3500.00, conferido aritmeticamente e nao copiado do payload.
                .andExpect(jsonPath("$.data.resumo.resultadoValor").value(4700.0 - 3500.0))
                .andExpect(jsonPath("$.data.resumo.lancamentosEstornadosExcluidos").value(0))
                .andExpect(jsonPath("$.data.periodos.length()").value(1))
                .andExpect(jsonPath("$.data.periodos[0].inicio").value("2026-09-01"))
                .andExpect(jsonPath("$.data.periodos[0].fim").value("2026-09-30"))
                .andExpect(jsonPath("$.data.periodos[0].entradas.valor").value(3500.0))
                .andExpect(jsonPath("$.data.periodos[0].saidas.valor").value(4700.0))
                .andExpect(jsonPath("$.data.periodos[0].resultadoValor").value(1200.0));
    }

    /** CA-21: a serie nasce de {@code de}/{@code ate} — mes sem nenhuma linha aparece com zeros. */
    @Test
    void mesSemMovimentacaoVemComZeros() throws Exception {
        comValor("ENTRADA", "2026-09-05 10:00", "10", "350.00", "3500.00", null);

        chamar("?de=2026-08-01&ate=2026-10-31", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periodos.length()").value(3))
                .andExpect(jsonPath("$.data.periodos[0].inicio").value("2026-08-01"))
                .andExpect(jsonPath("$.data.periodos[0].fim").value("2026-08-31"))
                .andExpect(jsonPath("$.data.periodos[0].entradas.lancamentos").value(0))
                .andExpect(jsonPath("$.data.periodos[0].entradas.quantidade").value(0.0))
                .andExpect(jsonPath("$.data.periodos[0].entradas.valor").value(0.0))
                .andExpect(jsonPath("$.data.periodos[0].resultadoValor").value(0.0))
                .andExpect(jsonPath("$.data.periodos[1].entradas.valor").value(3500.0))
                .andExpect(jsonPath("$.data.periodos[2].inicio").value("2026-10-01"))
                .andExpect(jsonPath("$.data.periodos[2].fim").value("2026-10-31"))
                .andExpect(jsonPath("$.data.periodos[2].saidas.lancamentos").value(0));
    }

    /**
     * CA-22: baldes de segunda a domingo, com o primeiro e o ultimo <b>recortados</b> por
     * {@code de}/{@code ate}. {@code 2026-09-16} e QUARTA: o balde comeca nela, mas agrupa pela
     * segunda-feira.
     */
    @Test
    void semanaSegundaADomingo() throws Exception {
        comValor("ENTRADA", "2026-09-17 10:00", "1", "100.00", "100.00", null);
        comValor("SAIDA", "2026-09-22 10:00", "1", "250.00", "250.00", null);

        chamar("?de=2026-09-16&ate=2026-09-27&granularidade=SEMANA", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.granularidade").value("SEMANA"))
                .andExpect(jsonPath("$.data.periodos.length()").value(2))
                .andExpect(jsonPath("$.data.periodos[0].inicio").value("2026-09-16"))
                .andExpect(jsonPath("$.data.periodos[0].fim").value("2026-09-20"))
                .andExpect(jsonPath("$.data.periodos[0].entradas.valor").value(100.0))
                .andExpect(jsonPath("$.data.periodos[0].saidas.valor").value(0.0))
                .andExpect(jsonPath("$.data.periodos[1].inicio").value("2026-09-21"))
                .andExpect(jsonPath("$.data.periodos[1].fim").value("2026-09-27"))
                .andExpect(jsonPath("$.data.periodos[1].saidas.valor").value(250.0))
                .andExpect(jsonPath("$.data.resumo.saidas.valor").value(250.0));
    }

    // ---- CA-24 / CA-55: o par estornado ------------------------------------------------------

    /**
     * CA-24: par <b>inteiro dentro</b> do periodo — as DUAS linhas saem, e o lancamento normal
     * <b>fica</b>. A fixture tem lancamento bom + par de propósito: so com par estornado, uma
     * implementacao que "soma zero sempre" passaria.
     *
     * <p>Assere as duas pernas: {@code entradas.valor} (mutacao §10 #10b solta a ORIGINAL) e
     * {@code saidas.valor} (mutacao §10 #10c solta o ESTORNO) — os erros vao em direcoes contrarias.
     */
    @Test
    void parEstornadoNaoEntraNoTotal() throws Exception {
        comValor("ENTRADA", "2026-09-05 10:00", "10", "350.00", "3500.00", null);
        Long errada = comValor("ENTRADA", "2026-09-10 10:00", "2", "50.00", "100.00", null);
        comValor("SAIDA", "2026-09-11 10:00", "2", "50.00", "100.00", errada);

        String corpo = chamar(SETEMBRO, admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumo.entradas.valor").value(3500.0))
                .andExpect(jsonPath("$.data.resumo.entradas.lancamentos").value(1))
                .andExpect(jsonPath("$.data.resumo.saidas.valor").value(0.0))
                .andExpect(jsonPath("$.data.resumo.saidas.lancamentos").value(0))
                .andExpect(jsonPath("$.data.resumo.resultadoValor").value(-3500.0))
                .andExpect(jsonPath("$.data.resumo.lancamentosEstornadosExcluidos").value(2))
                .andReturn().getResponse().getContentAsString();

        identidadeFecha(corpo, "2026-09-01", "2026-09-30");
    }

    /**
     * CA-55 (cenario 2): lancamento DENTRO, estorno FORA (mes seguinte). O {@code NOT EXISTS} e
     * <b>irrestrito</b>, entao o mes ja fechado <b>corrige retroativamente</b> (decisao PA#5 do dono)
     * — e o contador vale <b>1</b>, porque so uma linha do par estava no recorte.
     */
    @Test
    void straddleLancamentoDentroEstornoFora() throws Exception {
        comValor("ENTRADA", "2026-09-05 10:00", "10", "350.00", "3500.00", null);
        Long errada = comValor("ENTRADA", "2026-09-10 10:00", "2", "50.00", "100.00", null);
        comValor("SAIDA", "2026-10-02 10:00", "2", "50.00", "100.00", errada);

        String corpo = chamar(SETEMBRO, admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumo.entradas.valor").value(3500.0))
                .andExpect(jsonPath("$.data.resumo.entradas.lancamentos").value(1))
                .andExpect(jsonPath("$.data.resumo.saidas.valor").value(0.0))
                .andExpect(jsonPath("$.data.resumo.lancamentosEstornadosExcluidos").value(1))
                .andReturn().getResponse().getContentAsString();

        identidadeFecha(corpo, "2026-09-01", "2026-09-30");
    }

    /**
     * CA-55 (cenario 3): estorno DENTRO, lancamento FORA (mes anterior). Espelho coerente do anterior
     * — o mes corrente <b>nao</b> ganha a saida fantasma do estorno, e o contador tambem vale 1.
     */
    @Test
    void straddleEstornoDentroLancamentoFora() throws Exception {
        comValor("ENTRADA", "2026-09-05 10:00", "10", "350.00", "3500.00", null);
        Long errada = comValor("ENTRADA", "2026-08-25 10:00", "2", "50.00", "100.00", null);
        comValor("SAIDA", "2026-09-03 10:00", "2", "50.00", "100.00", errada);

        String corpo = chamar(SETEMBRO, admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumo.entradas.valor").value(3500.0))
                .andExpect(jsonPath("$.data.resumo.saidas.valor").value(0.0))
                .andExpect(jsonPath("$.data.resumo.saidas.lancamentos").value(0))
                .andExpect(jsonPath("$.data.resumo.lancamentosEstornadosExcluidos").value(1))
                .andReturn().getResponse().getContentAsString();

        identidadeFecha(corpo, "2026-09-01", "2026-09-30");
    }

    /**
     * CA-55 (cenario 4): com {@code tipo=ENTRADA}, a linha de estorno (que e SAIDA) nem chega ao
     * recorte — o contador acompanha o <b>recorte</b>, nao o par, e vale 1.
     */
    @Test
    void filtroPorTipoContaSoAsLinhasDoRecorte() throws Exception {
        comValor("ENTRADA", "2026-09-05 10:00", "10", "350.00", "3500.00", null);
        Long errada = comValor("ENTRADA", "2026-09-10 10:00", "2", "50.00", "100.00", null);
        comValor("SAIDA", "2026-09-11 10:00", "2", "50.00", "100.00", errada);

        chamar(SETEMBRO + "&tipo=ENTRADA", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumo.entradas.valor").value(3500.0))
                .andExpect(jsonPath("$.data.resumo.saidas.lancamentos").value(0))
                .andExpect(jsonPath("$.data.resumo.lancamentosEstornadosExcluidos").value(1));
    }

    /**
     * CA-55 (cenario 5): a linha de estorno <b>nao tem contraparte</b> (AD-SQ-156), entao num recorte
     * por fornecedor so a original aparecia — e ela sai pelo {@code NOT EXISTS}. O total por
     * fornecedor ja vem liquido do estorno, com contador 1.
     */
    @Test
    void filtroPorFornecedorJaVemLiquidoDoEstorno() throws Exception {
        Long comprada = comFornecedor("2026-09-08 10:00", "4", "25.00", "100.00");
        comValor("SAIDA", "2026-09-09 10:00", "4", "25.00", "100.00", comprada);

        chamar(SETEMBRO + "&fornecedorId=" + fornecedor, admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumo.entradas.lancamentos").value(0))
                .andExpect(jsonPath("$.data.resumo.entradas.valor").value(0.0))
                .andExpect(jsonPath("$.data.resumo.lancamentosEstornadosExcluidos").value(1));
    }

    // ---- CA-23: o primeiro GET ADMIN-only do projeto -----------------------------------------

    /** CA-23: ADMIN le o relatorio. */
    @Test
    void adminLeRelatorio() throws Exception {
        chamar(SETEMBRO, admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resumo").exists());
    }

    /** CA-23: USER autenticado leva 403 — sem o matcher do §3.9, esta rota devolveria 200. */
    @Test
    void userNaoLeRelatorio() throws Exception {
        chamar(SETEMBRO, user)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /** CA-23: sem token, 401 (vem do entry point, independe do matcher). */
    @Test
    void semTokenNaoLeRelatorio() throws Exception {
        mockMvc.perform(get(BASE + SETEMBRO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---- CA-25 / CA-54 / §3.7: os 400 dos parametros -----------------------------------------

    /** CA-25: 367 dias de intervalo ⇒ 400 {@code field=ate}. */
    @Test
    void intervaloDe367DiasE400() throws Exception {
        chamar("?de=2026-01-01&ate=2027-01-03", admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("ate"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("O intervalo do relatório não pode passar de 366 dias."));
    }

    /** CA-25, o outro lado da fronteira (R4): exatamente 366 dias <b>passa</b>. */
    @Test
    void intervaloDe366DiasPassa() throws Exception {
        chamar("?de=2026-01-01&ate=2027-01-02", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periodos.length()").value(13));
    }

    /** CA-54: {@code de} ausente e 400 com {@code field=de} — <b>nunca</b> 500 (§3.7-a0). */
    @Test
    void deAusenteE400() throws Exception {
        String corpo = chamar("?ate=2026-09-30", admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value("de"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("Informe a data inicial do período (de)."))
                .andReturn().getResponse().getContentAsString();

        semVazamentoTecnico(corpo);
    }

    /** CA-54: {@code ate} ausente e 400 com {@code field=ate}. */
    @Test
    void ateAusenteE400() throws Exception {
        chamar("?de=2026-09-01", admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value("ate"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("Informe a data final do período (ate)."));
    }

    /** CA-54: os dois ausentes ⇒ <b>dois</b> itens, em ordem deterministica ({@code de} primeiro). */
    @Test
    void deEAteAusentesDevolvemDoisDetalhesComDePrimeiro() throws Exception {
        String corpo = chamar("", admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.length()").value(2))
                .andExpect(jsonPath("$.error.details[0].field").value("de"))
                .andExpect(jsonPath("$.error.details[1].field").value("ate"))
                .andReturn().getResponse().getContentAsString();

        semVazamentoTecnico(corpo);
    }

    /** §3.7-a1: o 400 de {@code de > ate} vem da fabrica reusada da T-M7-03 — mesmo campo, mesmo texto. */
    @Test
    void deMaiorQueAteE400() throws Exception {
        chamar("?de=2026-09-30&ate=2026-09-01", admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("de"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("A data inicial não pode ser maior que a final."));
    }

    /** §3.7: conjunto fechado de {@code granularidade}. */
    @Test
    void granularidadeForaDoConjuntoE400() throws Exception {
        chamar(SETEMBRO + "&granularidade=DIA", admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("granularidade"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("granularidade deve ser um de: SEMANA, MES"));
    }

    // ---- Apoio -------------------------------------------------------------------------------

    private ResultActions chamar(String query, String bearer) throws Exception {
        return mockMvc.perform(get(BASE + query).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    /**
     * §10 #10f: {@code somados + excluidos = total de linhas do recorte}, com o total vindo de um
     * {@code count} INDEPENDENTE (JDBC), nao da query que esta sob teste.
     */
    private void identidadeFecha(String corpo, String de, String ate) {
        long somados = numero(corpo, "$.data.resumo.entradas.lancamentos")
                + numero(corpo, "$.data.resumo.saidas.lancamentos")
                + numero(corpo, "$.data.resumo.ajustes.lancamentos");
        long excluidos = numero(corpo, "$.data.resumo.lancamentosEstornadosExcluidos");
        assertEquals(linhasDoRecorte(de, ate), somados + excluidos,
                "somados + excluidos tem de fechar com o total de linhas do recorte (§10 #10f)");
    }

    private long numero(String corpo, String caminho) {
        return ((Number) JsonPath.read(corpo, caminho)).longValue();
    }

    private long linhasDoRecorte(String de, String ate) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM movimentacao_estoque "
                        + "WHERE criado_em >= CAST(? AS timestamp) AT TIME ZONE 'America/Sao_Paulo' "
                        + "AND criado_em < (CAST(? AS timestamp) + interval '1 day') "
                        + "AT TIME ZONE 'America/Sao_Paulo'",
                Long.class, de + " 00:00:00", ate + " 00:00:00");
    }

    /** CA-54: erro de cliente nao vaza excecao nem stacktrace (§9). */
    private void semVazamentoTecnico(String corpo) {
        assertFalse(corpo.contains("Exception"), "o corpo nao pode citar excecao");
        assertFalse(corpo.contains("java.lang"), "o corpo nao pode citar pacote da JDK");
        assertFalse(corpo.contains("at com."), "o corpo nao pode trazer stacktrace");
    }

    private Long usuario(String nome, String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, nome, email, UUID.randomUUID().toString(), role);
    }

    /**
     * Linha do ledger COM dinheiro, com {@code criado_em} cravado em {@code America/Sao_Paulo}.
     * {@code total_bruto = total_final} (sem desconto), respeitando {@code ck_mov_totais_par} e
     * {@code ck_mov_total_final}.
     */
    private Long comValor(String tipo, String quando, String quantidade, String unitario,
            String total, Long estorna) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque (produto_id, produto_nome, tipo, quantidade, "
                        + "quantidade_resultante, usuario_nome, valor_unitario, total_bruto, "
                        + "total_final, estorna_movimentacao_id, criado_em) "
                        + "VALUES (?, 'Rosa Vermelha', ?, CAST(? AS numeric), 0, 'Ana Admin', "
                        + "CAST(? AS numeric), CAST(? AS numeric), CAST(? AS numeric), ?, "
                        + "CAST(? AS timestamp) AT TIME ZONE 'America/Sao_Paulo') RETURNING id",
                Long.class, rosa, tipo, quantidade, unitario, total, total, estorna, quando);
    }

    /** Linha sem dinheiro (as 5 colunas {@code NULL}, P6) — obrigatorio no AJUSTE (PA#1). */
    private Long semValor(String tipo, String quando, String quantidade) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque (produto_id, produto_nome, tipo, quantidade, "
                        + "quantidade_resultante, usuario_nome, criado_em) "
                        + "VALUES (?, 'Rosa Vermelha', ?, CAST(? AS numeric), 0, 'Ana Admin', "
                        + "CAST(? AS timestamp) AT TIME ZONE 'America/Sao_Paulo') RETURNING id",
                Long.class, rosa, tipo, quantidade, quando);
    }

    /** ENTRADA com contraparte (V10) — a linha de estorno dela NAO tem fornecedor (AD-SQ-156). */
    private Long comFornecedor(String quando, String quantidade, String unitario, String total) {
        return jdbc.queryForObject(
                "INSERT INTO movimentacao_estoque (produto_id, produto_nome, tipo, quantidade, "
                        + "quantidade_resultante, usuario_nome, fornecedor_id, fornecedor_nome, "
                        + "valor_unitario, total_bruto, total_final, criado_em) "
                        + "VALUES (?, 'Rosa Vermelha', 'ENTRADA', CAST(? AS numeric), 0, 'Ana Admin', "
                        + "?, 'Sitio Boa Flor', CAST(? AS numeric), CAST(? AS numeric), "
                        + "CAST(? AS numeric), CAST(? AS timestamp) AT TIME ZONE 'America/Sao_Paulo') "
                        + "RETURNING id",
                Long.class, rosa, quantidade, fornecedor, unitario, total, total, quando);
    }
}
