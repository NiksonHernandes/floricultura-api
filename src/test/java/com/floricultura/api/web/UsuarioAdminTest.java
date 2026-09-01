package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Gestao de usuarios pelo ADMIN — criar/listar/detalhar (SPEC-M1 §3.1/§3.2, CA-7/CA-8) contra um
 * PostgreSQL de DESCARTE (Testcontainers postgres:16), com o {@code UsuarioController}/
 * {@code UsuarioService}, {@code SecurityConfig} endurecido e filtro JWT reais no contexto. O token
 * ADMIN e emitido pelo {@link JwtService} (mesmo bean/segredo do filtro) para atravessar o RBAC de
 * {@code /usuarios/**} (ja provado no {@code AuthSecurityTest} — aqui exercitamos a regra de negocio).
 *
 * <p>Nome {@code *Test} (Surefire), como as demais integracoes Testcontainers do repo. <b>Sem segredo
 * versionado (§9):</b> os {@code senha_hash} de seed sao BCrypt de {@link UUID} aleatorios de runtime.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UsuarioAdminTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Segredo test-only (NAO-segredo; >= 32 bytes) do JwtService que emite o token ADMIN do teste.
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-usradmin-0123456789ab");
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
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirAdmin("admin-usr@floricultura.local");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
    }

    private Long inserirAdmin(String email) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, 'ADMIN', true, false) RETURNING id",
                Long.class,
                "Administrador",
                email,
                ENCODER.encode(UUID.randomUUID().toString()));
    }

    private String criarBody(String nome, String email, String senha, String role) {
        String r = role == null ? "" : ",\"role\":\"" + role + "\"";
        return "{\"nome\":\"" + nome + "\",\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"" + r + "}";
    }

    // ---- CA-7: criar --------------------------------------------------------------------------

    @Test
    void criar_comPayloadValido_devolve201SemHashRoleDefaultUserEProvisoria() throws Exception {
        mockMvc.perform(post("/api/v1/usuarios")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(criarBody("Maria Silva", "maria@floricultura.local", "senhaForte1", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.nome").value("Maria Silva"))
                .andExpect(jsonPath("$.data.email").value("maria@floricultura.local"))
                .andExpect(jsonPath("$.data.role").value("USER")) // default quando omitido (§3.2)
                .andExpect(jsonPath("$.data.ativo").value(true))
                .andExpect(jsonPath("$.data.senhaProvisoria").value(true)) // criacao por ADMIN (§4)
                .andExpect(jsonPath("$.data.criadoEm").isNotEmpty()) // vem do DEFAULT now() do banco
                // §9/CA-7: nunca expor senha_hash/senha no corpo.
                .andExpect(jsonPath("$.data.senhaHash").doesNotExist())
                .andExpect(jsonPath("$.data.senha").doesNotExist());
    }

    @Test
    void criar_comRoleAdmin_persisteRoleInformada() throws Exception {
        mockMvc.perform(post("/api/v1/usuarios")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(criarBody("Joao Chefe", "joao@floricultura.local", "senhaForte1", "ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void criar_comEmailDuplicado_devolve409ComMensagemAmigavel() throws Exception {
        String body = criarBody("Ana Dup", "ana@floricultura.local", "senhaForte1", null);
        mockMvc.perform(post("/api/v1/usuarios")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/usuarios")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("E-mail ja cadastrado."));
    }

    @Test
    void criar_comSenhaCurta_devolve400ComDetails() throws Exception {
        mockMvc.perform(post("/api/v1/usuarios")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(criarBody("Pedro Curto", "pedro@floricultura.local", "123", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("senha"));
    }

    @Test
    void criar_comEmailMalformado_devolve400Validacao() throws Exception {
        mockMvc.perform(post("/api/v1/usuarios")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(criarBody("Sem Arroba", "email-invalido", "senhaForte1", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("email"));
    }

    // ---- CA-8: listar / detalhar --------------------------------------------------------------

    @Test
    void listar_devolveOrdenadoPorNomeSemHash() throws Exception {
        // Insere fora de ordem alfabetica; a lista deve voltar por nome (§3.2).
        criar("Zelia", "zelia@floricultura.local");
        criar("Bruno", "bruno@floricultura.local");

        mockMvc.perform(get("/api/v1/usuarios").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // adminId(Administrador) + Bruno + Zelia = 3, ordenados por nome.
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].nome").value("Administrador"))
                .andExpect(jsonPath("$.data[1].nome").value("Bruno"))
                .andExpect(jsonPath("$.data[2].nome").value("Zelia"))
                // §9/CA-8: nenhum item vaza senha_hash.
                .andExpect(jsonPath("$.data[0].senhaHash").doesNotExist())
                .andExpect(jsonPath("$.data[1].senhaHash").doesNotExist());
    }

    @Test
    void detalhar_existente_devolve200SemHash() throws Exception {
        Long id = criar("Carla Detalhe", "carla@floricultura.local");

        mockMvc.perform(get("/api/v1/usuarios/" + id).header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.nome").value("Carla Detalhe"))
                .andExpect(jsonPath("$.data.email").value("carla@floricultura.local"))
                .andExpect(jsonPath("$.data.criadoEm").isNotEmpty())
                .andExpect(jsonPath("$.data.senhaHash").doesNotExist());
    }

    @Test
    void detalhar_inexistente_devolve404() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios/999999").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- helper: cria via API e devolve o id gerado -------------------------------------------

    private Long criar(String nome, String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/usuarios")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(criarBody(nome, email, "senhaForte1", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int i = body.indexOf("\"id\":") + 5;
        int j = i;
        while (j < body.length() && Character.isDigit(body.charAt(j))) {
            j++;
        }
        Long id = Long.valueOf(body.substring(i, j));
        assertThat(id).isPositive();
        return id;
    }
}
