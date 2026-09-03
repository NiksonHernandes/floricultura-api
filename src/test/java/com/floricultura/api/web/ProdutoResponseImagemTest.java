package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Fatia read da LISTA para o invariante central do M3 (SPEC-M3 §5 CA-9 / §6, AD-SQ-38): o binario
 * fica FORA das leituras. Fecha BUG-001 — o detalhe ({@code GET /produtos/{id}}) ja tinha rede em
 * {@code ProdutoImagemApiTest}, mas a LISTA ({@code GET /produtos}) nao. Contra um PostgreSQL de
 * DESCARTE (Testcontainers postgres:16) com {@code SecurityConfig}/filtro JWT reais, cria via API 2
 * produtos (um COM imagem via {@code POST .../imagem} de JPEG valido, um SEM) e prova que cada item
 * de {@code data.conteudo} traz {@code temImagem} (true/false) e que o payload da lista NUNCA carrega
 * o campo binario {@code "imagem":} (bytes/base64) — {@code imagemUrl} e {@code temImagem} sao
 * permitidos. Reforca o mesmo invariante no detalhe.
 *
 * <p>Nome {@code *Test} (Surefire): o repo nao configura Failsafe — integracoes Testcontainers usam
 * {@code *Test} para rodarem no {@code clean verify} (mesma nota de {@code ProdutoImagemApiTest}).
 * Segredos de teste sao NAO-segredos (BCrypt de {@link UUID} de runtime).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoResponseImagemTest {

    /** Bytes de uma "imagem" JPEG minima (magic FF D8 FF + payload) — mesmo helper do ImagemApiTest. */
    private static final byte[] IMAGEM_JPEG =
            new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03, 0x04};

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-produtoresponse-0123456789ab");
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
        jdbc.update("DELETE FROM movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("admin-resp@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("user-resp@floricultura.local", "USER");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    /** Cria um produto via API (ADMIN, POST /produtos) e devolve o id gerado (buscado por nome). */
    private Long criarProduto(String nome) throws Exception {
        String corpo = "{\"nome\":\"" + nome + "\",\"unidadeMedida\":\"un\",\"estoqueMinimo\":1}";
        mockMvc.perform(post("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nome").value(nome));
        return jdbc.queryForObject("SELECT id FROM produto WHERE nome = ?", Long.class, nome);
    }

    /** Sobe um JPEG valido no produto via API (ADMIN, POST /produtos/{id}/imagem). */
    private void enviarImagem(Long id) throws Exception {
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.jpg", MediaType.IMAGE_JPEG_VALUE, IMAGEM_JPEG);
        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temImagem").value(true));
    }

    // ---- CA-9 (fatia LISTA): temImagem por item + zero binario no payload -------------------

    @Test
    void listar_trazTemImagemPorItemENaoOBinario() throws Exception {
        // "Aurora..." < "Zilon..." (ordem nome ASC): indice 0 = com imagem, indice 1 = sem.
        Long comImagem = criarProduto("Aurora Com Imagem");
        enviarImagem(comImagem);
        criarProduto("Zilon Sem Imagem");

        String corpo = mockMvc.perform(get("/api/v1/produtos")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.conteudo").isArray())
                .andExpect(jsonPath("$.data.conteudo.length()").value(2))
                .andExpect(jsonPath("$.data.conteudo[0].nome").value("Aurora Com Imagem"))
                .andExpect(jsonPath("$.data.conteudo[0].temImagem").value(true))
                .andExpect(jsonPath("$.data.conteudo[1].nome").value("Zilon Sem Imagem"))
                .andExpect(jsonPath("$.data.conteudo[1].temImagem").value(false))
                .andReturn().getResponse().getContentAsString();

        // O binario NUNCA vaza na lista: nem o campo "imagem": (bytes) nem "imagem":" (base64).
        // "imagemUrl" e "temImagem" sao permitidos e nao casam com "imagem": (i minusculo / :).
        assertThat(corpo).doesNotContain("\"imagem\":");
        assertThat(corpo).doesNotContain("\"imagem\":\"");
    }

    // ---- Reforco: o mesmo invariante no detalhe (GET /produtos/{id}) ------------------------

    @Test
    void detalhar_trazTemImagemENaoOBinario() throws Exception {
        Long comImagem = criarProduto("Rosa Com Imagem");
        enviarImagem(comImagem);

        String corpo = mockMvc.perform(get("/api/v1/produtos/" + comImagem)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temImagem").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo).doesNotContain("\"imagem\":");
        assertThat(corpo).doesNotContain("\"imagem\":\"");
    }
}
