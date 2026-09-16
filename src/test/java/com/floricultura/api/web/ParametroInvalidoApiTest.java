package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
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
 * D3 (SPEC-M6.1 §3.3, CA-10..CA-13): parametro de tipo incompativel devolve <b>400
 * VALIDATION_ERROR</b>, e nao o 500 do catch-all, nas <b>duas</b> portas de entrada —
 * {@code @PathVariable} ({@code /produtos/abc}, {@code /cores/abc}) e {@code @RequestParam}
 * ({@code ?precoMin=abc}, {@code ?tamanho=x}, {@code ?semPreco=talvez}, {@code ?corIds=a}).
 *
 * <p>O teste bate na aplicacao real (Testcontainers postgres:16, filtro JWT e advice de verdade)
 * porque o defeito mora justamente na CADEIA: a excecao de conversao do Spring so aparece quando o
 * {@code DispatcherServlet} tenta vincular o argumento. Um teste unitario do handler provaria o
 * corpo, mas nunca que a excecao chega ate ele.
 *
 * <p>O ultimo caso e a fronteira do conserto: id numerico inexistente continua <b>404</b>, enum fora
 * do conjunto continua <b>400 com a mensagem de DOMINIO</b> (nao a generica do handler novo) e rota
 * protegida sem token continua <b>401</b> — a D3 nao encosta em nenhum desses caminhos (R7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ParametroInvalidoApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static final String PRODUTOS = "/api/v1/produtos";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-parametroinvalido-0123456789");
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
        jdbc.update("DELETE FROM usuario");
        Long userId = jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, 'USER', true, false) RETURNING id",
                Long.class, "Nome USER", "user-tipo-invalido@floricultura.local",
                ENCODER.encode(UUID.randomUUID().toString()));
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    /** 400 no envelope, com o {@code field} esperado nos {@code details}. */
    private void espera400(String uri, String campo) throws Exception {
        mockMvc.perform(get(uri).header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value(campo))
                .andExpect(jsonPath("$.error.details[0].message").value("Valor invalido."));
    }

    // ---- CA-10: a porta do @PathVariable ------------------------------------------------------

    @Test
    void pathVariableNaoNumerico_devolve400ComFieldId() throws Exception {
        espera400(PRODUTOS + "/abc", "id");
        espera400("/api/v1/cores/abc", "id");
    }

    // ---- CA-11: a porta do @RequestParam ------------------------------------------------------

    @Test
    void queryParamDeTipoIncompativel_devolve400ComOFieldDoParametro() throws Exception {
        espera400(PRODUTOS + "?precoMin=abc", "precoMin");   // BigDecimal
        espera400(PRODUTOS + "?tamanho=x", "tamanho");       // Integer
        espera400(PRODUTOS + "?semPreco=talvez", "semPreco"); // Boolean
        espera400(PRODUTOS + "?corIds=a", "corIds");         // List<Long>
    }

    // ---- CA-12: o 400 nao vaza detalhe tecnico ------------------------------------------------

    @Test
    void corpoDoErro_naoVazaDetalheTecnico_masIdentificaOCodigo() throws Exception {
        String corpo = mockMvc.perform(get(PRODUTOS + "/abc")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        // O que NAO pode sobrar: a mensagem do Spring e "Failed to convert value of type
        // 'java.lang.String' to required type 'java.lang.Long'" — nome de classe, stacktrace e
        // jargao de framework sao vazamento (SPEC-M0 §9).
        assertThat(corpo)
                .doesNotContain("java.lang")
                .doesNotContain("Failed to convert")
                .doesNotContain("Exception")
                .doesNotContain("at com.")
                .doesNotContain("MethodArgument");
        // O que TEM de estar la: o codigo do envelope e o caminho pedido — sem isso o cliente
        // recebe um 400 mudo e o teste acima passaria ate com corpo vazio.
        assertThat(corpo)
                .contains("\"code\":\"VALIDATION_ERROR\"")
                .contains("/api/v1/produtos/abc");
    }

    // ---- CA-13: a fronteira — o que a D3 NAO altera -------------------------------------------

    @Test
    void naoRegressao_404DeIdInexistente_400DeDominio_e401SemToken() throws Exception {
        // Id NUMERICO inexistente: converte, chega ao service, continua 404.
        mockMvc.perform(get(PRODUTOS + "/999999").header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        // Enum fora do conjunto: converte (e String), quem recusa e o dominio — a mensagem tem de
        // continuar sendo a de DOMINIO, nao a generica "Valor invalido." do handler novo.
        mockMvc.perform(get(PRODUTOS + "?estoque=XPTO").header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("estoque"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("Deve ser um de: SEM_ESTOQUE, BAIXO, COM_ESTOQUE."));

        // Sem token o filtro corta ANTES do binding: tipo invalido nao vira 400 para anonimo.
        mockMvc.perform(get(PRODUTOS + "/abc")).andExpect(status().isUnauthorized());
    }
}
