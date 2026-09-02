package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    void criar_comPayloadValido_devolve201SemHashRoleUserEProvisoriaFalse() throws Exception {
        mockMvc.perform(post("/api/v1/usuarios")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(criarBody("Maria Silva", "maria@floricultura.local", "senhaForte1", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.nome").value("Maria Silva"))
                .andExpect(jsonPath("$.data.email").value("maria@floricultura.local"))
                .andExpect(jsonPath("$.data.role").value("USER")) // API sempre cria USER (§3.2/AD-SQ-19)
                .andExpect(jsonPath("$.data.ativo").value(true))
                .andExpect(jsonPath("$.data.senhaProvisoria").value(false)) // AD-SQ-24: criacao NAO forca troca
                .andExpect(jsonPath("$.data.criadoEm").isNotEmpty()) // vem do DEFAULT now() do banco
                // §9/CA-7: nunca expor senha_hash/senha no corpo.
                .andExpect(jsonPath("$.data.senhaHash").doesNotExist())
                .andExpect(jsonPath("$.data.senha").doesNotExist());
    }

    /**
     * Regra do dono (2026-09-01 — §3.2/AD-SQ-19): a API <b>nunca</b> cria ADMIN. Um {@code role:"ADMIN"}
     * no payload e ignorado (campo inexistente no DTO) → nasce {@code USER}. Prova via resposta e via
     * banco (nenhum ADMIN alem do seed permanece).
     */
    @Test
    void criar_comRoleAdminNoPayload_eIgnorado_criaUser() throws Exception {
        mockMvc.perform(post("/api/v1/usuarios")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(criarBody("Joao Chefe", "joao@floricultura.local", "senhaForte1", "ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("USER"));

        // No banco, o unico ADMIN continua sendo o seed do @BeforeEach — o novo usuario e USER.
        Long admins = jdbc.queryForObject(
                "SELECT count(*) FROM usuario WHERE role = 'ADMIN'", Long.class);
        assertThat(admins).isEqualTo(1L);
        String roleJoao = jdbc.queryForObject(
                "SELECT role FROM usuario WHERE email = ?", String.class, "joao@floricultura.local");
        assertThat(roleJoao).isEqualTo("USER");
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

        // RETROFIT M2 (AD-SQ-29/CA-21, mudanca SANCIONADA): GET /usuarios deixa de devolver array e
        // passa a PaginaResponse — o array agora vive em $.data.conteudo e o total em
        // $.data.totalElementos. Ordenacao (nome ASC) e o nao-vazamento de senha_hash seguem provados.
        mockMvc.perform(get("/api/v1/usuarios").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // adminId(Administrador) + Bruno + Zelia = 3, ordenados por nome.
                .andExpect(jsonPath("$.data.totalElementos").value(3))
                .andExpect(jsonPath("$.data.conteudo.length()").value(3))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Administrador"))
                .andExpect(jsonPath("$.data.conteudo[1].nome").value("Bruno"))
                .andExpect(jsonPath("$.data.conteudo[2].nome").value("Zelia"))
                // §9/CA-8: nenhum item vaza senha_hash.
                .andExpect(jsonPath("$.data.conteudo[0].senhaHash").doesNotExist())
                .andExpect(jsonPath("$.data.conteudo[1].senhaHash").doesNotExist());
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

    // ---- CA-9: status (ativar/desativar + protecao do ultimo ADMIN) ---------------------------

    @Test
    void alterarStatus_desativaUsuarioNaoCritico_devolve200Inativo() throws Exception {
        Long id = criar("Nina Ativa", "nina@floricultura.local");

        mockMvc.perform(patch("/api/v1/usuarios/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ativo\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.ativo").value(false))
                .andExpect(jsonPath("$.data.senhaHash").doesNotExist());

        Boolean ativo = jdbc.queryForObject(
                "SELECT ativo FROM usuario WHERE id = ?", Boolean.class, id);
        assertThat(ativo).isFalse();
    }

    /**
     * A protecao morde so o <b>unico</b> ADMIN ativo (§4/CA-9): havendo 2 ADMINs ativos, desativar um
     * deles retorna 200 (a contagem de ADMINs ativos era 2, nao 1).
     */
    @Test
    void alterarStatus_desativaAdminHavendoOutroAtivo_devolve200() throws Exception {
        Long segundoAdmin = inserirAdmin("admin2@floricultura.local");

        mockMvc.perform(patch("/api/v1/usuarios/" + segundoAdmin + "/status")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ativo\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ativo").value(false));
    }

    /** Desativar o unico ADMIN ativo (o seed do @BeforeEach) → 409 CONFLICT com mensagem canonica. */
    @Test
    void alterarStatus_desativaUnicoAdminAtivo_devolve409() throws Exception {
        Long idAdmin = jdbc.queryForObject(
                "SELECT id FROM usuario WHERE email = ?", Long.class, "admin-usr@floricultura.local");

        mockMvc.perform(patch("/api/v1/usuarios/" + idAdmin + "/status")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ativo\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message")
                        .value("Nao e possivel desativar o unico administrador ativo."));

        // Regra transacional: negou ANTES de persistir → o admin continua ativo.
        Boolean ativo = jdbc.queryForObject(
                "SELECT ativo FROM usuario WHERE id = ?", Boolean.class, idAdmin);
        assertThat(ativo).isTrue();
    }

    @Test
    void alterarStatus_inexistente_devolve404() throws Exception {
        mockMvc.perform(patch("/api/v1/usuarios/999999/status")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ativo\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---- CA-11: reset de senha pelo ADMIN -----------------------------------------------------

    @Test
    void redefinirSenha_devolve204ENaoForcaProvisoria() throws Exception {
        // Alvo comeca com senha_provisoria=false e um hash conhecido, para provar a mudanca.
        String hashAntigo = ENCODER.encode("senhaAntiga1");
        Long id = jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, 'USER', true, false) RETURNING id",
                Long.class, "Otavio Reset", "otavio@floricultura.local", hashAntigo);

        mockMvc.perform(patch("/api/v1/usuarios/" + id + "/senha")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"novaSenha\":\"novaSenhaForte1\"}"))
                .andExpect(status().isNoContent());

        Boolean provisoria = jdbc.queryForObject(
                "SELECT senha_provisoria FROM usuario WHERE id = ?", Boolean.class, id);
        assertThat(provisoria).isFalse(); // AD-SQ-24: reset NAO forca troca (flag so informativa)

        // O hash foi de fato reescrito como BCrypt da nova senha (nunca a senha antiga/texto — §9).
        String hashNovo = jdbc.queryForObject(
                "SELECT senha_hash FROM usuario WHERE id = ?", String.class, id);
        assertThat(hashNovo).isNotEqualTo(hashAntigo);
        assertThat(ENCODER.matches("novaSenhaForte1", hashNovo)).isTrue();
    }

    @Test
    void redefinirSenha_novaSenhaCurta_devolve400() throws Exception {
        Long id = criar("Bia Curta", "bia@floricultura.local");

        mockMvc.perform(patch("/api/v1/usuarios/" + id + "/senha")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"novaSenha\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("novaSenha"));
    }

    @Test
    void redefinirSenha_inexistente_devolve404() throws Exception {
        mockMvc.perform(patch("/api/v1/usuarios/999999/senha")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"novaSenha\":\"novaSenhaForte1\"}"))
                .andExpect(status().isNotFound())
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
