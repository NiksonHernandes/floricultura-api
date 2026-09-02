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
 * Retrofit da lista de usuarios para paginacao (T-M2-5, CA-21/AD-SQ-29) contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16), com {@code UsuarioController}/{@code UsuarioService},
 * {@code SecurityConfig} endurecido e filtro JWT reais no contexto. Exercita a superficie <b>nova</b>
 * do {@code GET /api/v1/usuarios}: envelope {@code PaginaResponse} ({@code conteudo}/{@code
 * totalElementos}/...), defaults {@code pagina=0}/{@code tamanho=20}, ordenacao {@code nome ASC},
 * paginacao, filtro {@code ILIKE '%nome%'}, range invalido → 400 e o RBAC {@code /usuarios/**} =
 * {@code ROLE_ADMIN} (USER → 403). A regressao de criar/status/reset/ultimo-admin/seed permanece no
 * {@code UsuarioAdminTest} (intacta — AD-SQ-29 delimita o retrofit ao formato de listagem).
 *
 * <p>Nome {@code *Test} (Surefire): o repo <b>nao</b> configura Failsafe — todas as integracoes
 * Testcontainers rodam sob o Surefire no {@code verify} (um {@code *IT} ficaria de fora). <b>Sem
 * segredo versionado (§9):</b> os {@code senha_hash} de seed sao BCrypt de {@link UUID} aleatorios.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UsuarioPaginacaoTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Segredo test-only (NAO-segredo; >= 32 bytes) do JwtService que emite os tokens do teste.
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-usrpaginacao-0123456789ab");
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
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("Administrador", "admin-pag@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("Usuario Comum", "user-pag@floricultura.local", "USER");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    private Long inserirUsuario(String nome, String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, nome, email, ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    // ---- CA-21: shape do PaginaResponse + defaults + ordenacao nome ASC -----------------------

    @Test
    void listar_comTokenAdmin_devolvePaginaResponseOrdenadoPorNome() throws Exception {
        // seed = Administrador + Usuario Comum; adiciona fora de ordem alfabetica.
        inserirUsuario("Zelia", "zelia@floricultura.local", "USER");
        inserirUsuario("Bruno", "bruno@floricultura.local", "USER");

        // Administrador, Bruno, Usuario Comum, Zelia = 4, ordenados por nome ASC.
        mockMvc.perform(get("/api/v1/usuarios").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.conteudo").isArray())
                .andExpect(jsonPath("$.data.pagina").value(0))
                .andExpect(jsonPath("$.data.tamanho").value(20))
                .andExpect(jsonPath("$.data.totalElementos").value(4))
                .andExpect(jsonPath("$.data.totalPaginas").value(1))
                .andExpect(jsonPath("$.data.primeira").value(true))
                .andExpect(jsonPath("$.data.ultima").value(true))
                .andExpect(jsonPath("$.data.conteudo.length()").value(4))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Administrador"))
                .andExpect(jsonPath("$.data.conteudo[1].nome").value("Bruno"))
                .andExpect(jsonPath("$.data.conteudo[2].nome").value("Usuario Comum"))
                .andExpect(jsonPath("$.data.conteudo[3].nome").value("Zelia"))
                // §9/CA-8: nenhum item vaza senha_hash.
                .andExpect(jsonPath("$.data.conteudo[0].senhaHash").doesNotExist());
    }

    // ---- CA-21 [RBAC]: /usuarios/** e ADMIN — USER 403, sem token 401 -------------------------

    @Test
    void listar_comTokenUser_devolve403() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios").header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void listar_semToken_devolve401() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---- CA-21: paginacao (fatia + pagina alem do total) --------------------------------------

    @Test
    void listar_segundaPagina_devolveFatiaCorreta() throws Exception {
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("Administrador", "admin-pag@floricultura.local", "ADMIN");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
        // Nome ASC: Administrador, U0, U1, U2, U3, U4, U5, U6 = 8; tamanho=5 → pagina 1 (offset 5)
        // traz U4, U5, U6.
        for (int i = 0; i < 7; i++) {
            inserirUsuario("U" + i, "u" + i + "@floricultura.local", "USER");
        }

        mockMvc.perform(get("/api/v1/usuarios?pagina=1&tamanho=5")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pagina").value(1))
                .andExpect(jsonPath("$.data.tamanho").value(5))
                .andExpect(jsonPath("$.data.totalElementos").value(8))
                .andExpect(jsonPath("$.data.totalPaginas").value(2))
                .andExpect(jsonPath("$.data.conteudo.length()").value(3))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("U4"))
                .andExpect(jsonPath("$.data.conteudo[2].nome").value("U6"))
                .andExpect(jsonPath("$.data.primeira").value(false))
                .andExpect(jsonPath("$.data.ultima").value(true));
    }

    @Test
    void listar_paginaAlemDoTotal_devolve200Vazio() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios?pagina=9&tamanho=5")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo.length()").value(0))
                .andExpect(jsonPath("$.data.ultima").value(true));
    }

    // ---- CA-21: range invalido → 400 VALIDATION_ERROR (handler local do controller) -----------

    @Test
    void listar_tamanhoZero_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios?tamanho=0")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("tamanho"));
    }

    @Test
    void listar_tamanhoAcimaDoMaximo_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios?tamanho=101")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listar_paginaNegativa_devolve400() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios?pagina=-1")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("pagina"));
    }

    // ---- CA-21: filtro ILIKE substring case-insensitive ---------------------------------------

    @Test
    void listar_comFiltroNome_aplicaIlikeSubstringCaseInsensitive() throws Exception {
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("Administrador", "admin-pag@floricultura.local", "ADMIN");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
        inserirUsuario("Marina", "marina@floricultura.local", "USER");
        inserirUsuario("Rafael", "rafael@floricultura.local", "USER");

        // nome=rin (minusculo) casa "Marina" (substring interna, case-insensitive), nao "Rafael".
        mockMvc.perform(get("/api/v1/usuarios?nome=rin")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(1))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Marina"));
    }

    @Test
    void listar_filtroSemMatch_devolve200Vazio() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios?nome=zzz-inexistente")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conteudo.length()").value(0))
                .andExpect(jsonPath("$.data.totalElementos").value(0));
    }
}
