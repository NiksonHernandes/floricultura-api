package com.floricultura.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Anti-regressao do filtro por nome de {@code /clientes} e {@code /fornecedores} (T-M6-05a): o termo
 * do usuario e <b>literal</b> — {@code %} e {@code _} nao viram curinga.
 *
 * <p>Por que existe: a {@code ContatoSpecs} do M6 substituiu a derived query
 * {@code findByNomeContainingIgnoreCase} (que escapava os wildcards por padrao do Spring Data) por
 * {@code cb.like} cru. Esta classe trava, <b>por efeito</b>, que o {@code FiltroTexto} restaurou a
 * semantica herdada — e que ela e a <b>mesma</b> de {@code /produtos}
 * ({@code ProdutoFiltroApiTest#filtroPorNome_escapaOsWildcardsDoTermo}).
 *
 * <p>Arquivo novo de proposito: o {@code ContatoFiltroApiTest} ja esta versionado e nao se edita
 * teste rastreado (anti-burla). Dados ficticios (LGPD).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ContatoNomeWildcardTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-contatowildcard-0123456789ab");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String userBearer;

    @BeforeEach
    void seed() {
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM cliente");
        jdbc.update("DELETE FROM fornecedor");
        jdbc.update("DELETE FROM usuario");
        Long userId = jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, 'USER', true, false) RETURNING id",
                Long.class, "Nome USER", "user-wildcard@floricultura.local",
                ENCODER.encode(UUID.randomUUID().toString()));
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    /** "Buque 50% Off" e "Buque 500 Hastes" no cadastro: so o 1o casa o termo literal "50%". */
    private void seedPar(String tabela) {
        jdbc.update("INSERT INTO " + tabela + " (nome) VALUES ('Buque 50% Off'), "
                + "('Buque 500 Hastes'), ('Lote_A'), ('LoteXA')");
    }

    private void esperaUnico(String rota, String termo, String nome) throws Exception {
        mockMvc.perform(get(rota).param("nome", termo)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value(nome));
    }

    @Test
    void clientes_filtroPorNome_naoTrataPercentEUnderscoreComoCuringa() throws Exception {
        seedPar("cliente");

        esperaUnico("/api/v1/clientes", "50%", "Buque 50% Off");
        esperaUnico("/api/v1/clientes", "Lote_A", "Lote_A");
    }

    @Test
    void fornecedores_filtroPorNome_naoTrataPercentEUnderscoreComoCuringa() throws Exception {
        seedPar("fornecedor");

        esperaUnico("/api/v1/fornecedores", "50%", "Buque 50% Off");
        esperaUnico("/api/v1/fornecedores", "Lote_A", "Lote_A");
    }
}
