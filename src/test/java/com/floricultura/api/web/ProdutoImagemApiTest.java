package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * Fatia de ENVIAR/SERVIR/REMOVER a imagem do produto (M3/T-M3-2+T-M3-3, CA-2..CA-8/CA-10 +
 * comportamento de CA-9) contra um PostgreSQL de DESCARTE (Testcontainers postgres:16), com
 * {@code ProdutoImagemController}/{@code ProdutoImagemService}, {@code SecurityConfig} endurecido e
 * filtro JWT reais. Prova: upload feliz ADMIN → 200 + {@code temImagem:true} + bytes gravados (CA-2);
 * USER 403 / sem token 401 (CA-3); tipo/spoof/vazio → 400 sem gravar (CA-4); inexistente → 404 (CA-6);
 * binario servido fora do envelope com headers exatos (CA-7); 404 — nunca 401 — sem imagem/inexistente
 * (CA-8); delete 204 idempotente + {@code temImagem} falso + GET 404 + USER 403 (CA-10); {@code
 * temImagem} no detalhe (CA-9). O limite de tamanho (CA-5) fica em {@code ProdutoImagemLimiteTest}
 * (precisa de {@code app.upload.imagem.max-bytes} reduzido). O teto do container (> 6MB) e dívida P2
 * (smoke manual — MockMvc nao enforca limite de container).
 *
 * <p>Nome {@code *Test} (Surefire): o repo nao configura Failsafe — integracoes Testcontainers usam
 * {@code *Test} para rodarem no {@code clean verify} (mesma nota de {@code ProdutoWriteTest}). Segredos
 * de teste sao NAO-segredos (BCrypt de {@link UUID} de runtime).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoImagemApiTest {

    /** Bytes de uma "imagem" JPEG minima (magic FF D8 FF + payload). Comparados byte a byte no CA-7. */
    private static final byte[] IMAGEM_JPEG =
            new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03, 0x04};
    private static final String IMAGEM_JPEG_HEX = "FFD8FF01020304";

    /** PNG valido (magic 89 50 4E 47 0D 0A 1A 0A + payload) — anti-spoofing CA-2/CA-4. */
    private static final byte[] IMAGEM_PNG =
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};
    /** WEBP valido: RIFF(0-3) + tamanho(4-7) + WEBP(8-11) — anti-spoofing CA-2/CA-4. */
    private static final byte[] IMAGEM_WEBP =
            new byte[] {0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50};

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-produtoimagem-0123456789ab");
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
        Long adminId = inserirUsuario("admin-imagem@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("user-imagem@floricultura.local", "USER");
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

    /** Insere um produto SEM imagem; devolve o id gerado. */
    private Long inserirProdutoSemImagem(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    /** Insere um produto COM imagem JPEG (bytea + content_type) direto no banco; devolve o id. */
    private Long inserirProdutoComImagem(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, imagem, imagem_content_type, imagem_filename) "
                        + "VALUES (?, 'un', decode(?, 'hex'), 'image/jpeg', 'foto.jpg') RETURNING id",
                Long.class, nome, IMAGEM_JPEG_HEX);
    }

    // ---- CA-7: servir binario + headers exatos ------------------------------------------------

    @Test
    void servir_comImagem_devolve200BinarioEHeaders() throws Exception {
        Long id = inserirProdutoComImagem("Rosa");

        byte[] corpo = mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, IMAGEM_JPEG.length))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline"))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "public, max-age=2592000, immutable"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(corpo).isEqualTo(IMAGEM_JPEG); // bytes identicos ao gravado (CA-7)
    }

    /** CA-7: o parametro de cache-busting {@code ?v=} do front e ignorado pelo endpoint. */
    @Test
    void servir_ignoraParametroV() throws Exception {
        Long id = inserirProdutoComImagem("Lirio");

        byte[] corpo = mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem?v=123456789")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(corpo).isEqualTo(IMAGEM_JPEG);
    }

    // ---- CA-8: sem imagem / inexistente -> 404 (nunca 401); sem token -> 401 ------------------

    @Test
    void servir_produtoSemImagem_devolve404() throws Exception {
        Long id = inserirProdutoSemImagem("Cravo");

        mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void servir_produtoInexistente_devolve404() throws Exception {
        mockMvc.perform(get("/api/v1/produtos/999999/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void servir_semToken_devolve401() throws Exception {
        Long id = inserirProdutoComImagem("Tulipa");

        mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---- CA-9 (P2 do review): temImagem no detalhe -------------------------------------------

    @Test
    void detalhe_comImagem_temImagemTrue() throws Exception {
        Long id = inserirProdutoComImagem("Girassol");

        mockMvc.perform(get("/api/v1/produtos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temImagem").value(true));
    }

    @Test
    void detalhe_semImagem_temImagemFalse() throws Exception {
        Long id = inserirProdutoSemImagem("Margarida");

        mockMvc.perform(get("/api/v1/produtos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temImagem").value(false));
    }

    // ---- CA-10: delete 204 idempotente + temImagem falso + GET 404 + USER 403 ----------------

    @Test
    void remover_comAdmin_devolve204ZeraImagemEGetSeguinte404() throws Exception {
        Long id = inserirProdutoComImagem("Azaleia");

        mockMvc.perform(delete("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        // temImagem caiu para false (CA-10)
        mockMvc.perform(get("/api/v1/produtos/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temImagem").value(false));

        // GET da imagem seguinte -> 404
        mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound());
    }

    /** CA-10: idempotente — produto existente SEM imagem ainda devolve 204. */
    @Test
    void remover_semImagem_devolve204() throws Exception {
        Long id = inserirProdutoSemImagem("Petunia");

        mockMvc.perform(delete("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());
    }

    @Test
    void remover_produtoInexistente_devolve404() throws Exception {
        mockMvc.perform(delete("/api/v1/produtos/999999/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void remover_comUser_devolve403() throws Exception {
        Long id = inserirProdutoComImagem("Violeta");

        mockMvc.perform(delete("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ---- CA-2: upload feliz (ADMIN) -> 200 + temImagem:true + bytes gravados ------------------

    @Test
    void enviar_jpegValidoComAdmin_devolve200TemImagemEGravaBytes() throws Exception {
        Long id = inserirProdutoSemImagem("Dalia");
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.jpg", MediaType.IMAGE_JPEG_VALUE, IMAGEM_JPEG);

        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.temImagem").value(true));

        // Bytes efetivamente gravados: o GET do binario devolve exatamente o enviado.
        byte[] servido = mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(servido).isEqualTo(IMAGEM_JPEG);
    }

    @Test
    void enviar_pngValido_devolve200() throws Exception {
        Long id = inserirProdutoSemImagem("Begonia");
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.png", MediaType.IMAGE_PNG_VALUE, IMAGEM_PNG);

        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temImagem").value(true));
    }

    @Test
    void enviar_webpValido_devolve200() throws Exception {
        Long id = inserirProdutoSemImagem("Hortensia");
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.webp", "image/webp", IMAGEM_WEBP);

        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temImagem").value(true));
    }

    /** CA-2: novo upload sobrescreve a imagem anterior (1 imagem/produto). */
    @Test
    void enviar_sobrescreveImagemAnterior() throws Exception {
        Long id = inserirProdutoComImagem("Camelia"); // ja tem JPEG
        MockMultipartFile novo =
                new MockMultipartFile("arquivo", "novo.png", MediaType.IMAGE_PNG_VALUE, IMAGEM_PNG);

        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(novo)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk());

        byte[] servido = mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(servido).isEqualTo(IMAGEM_PNG);
    }

    // ---- CA-3: RBAC do upload ----------------------------------------------------------------

    @Test
    void enviar_comUser_devolve403() throws Exception {
        Long id = inserirProdutoSemImagem("Jasmim");
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.jpg", MediaType.IMAGE_JPEG_VALUE, IMAGEM_JPEG);

        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void enviar_semToken_devolve401() throws Exception {
        Long id = inserirProdutoSemImagem("Lavanda");
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.jpg", MediaType.IMAGE_JPEG_VALUE, IMAGEM_JPEG);

        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    // ---- CA-4: tipo invalido / spoof / vazio -> 400 sem gravar -------------------------------

    @Test
    void enviar_tipoForaDaWhitelist_devolve400SemGravar() throws Exception {
        Long id = inserirProdutoSemImagem("Petunia2");
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.gif", "image/gif", IMAGEM_JPEG);

        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("arquivo"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("Tipo de imagem nao suportado (use JPG, PNG ou WEBP)."));

        // Nada gravado: o GET do binario continua 404.
        mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void enviar_magicBytesNaoCasam_devolve400SemGravar() throws Exception {
        Long id = inserirProdutoSemImagem("Anturio");
        // Declara image/jpeg mas o conteudo e PNG -> spoof.
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "fake.jpg", MediaType.IMAGE_JPEG_VALUE, IMAGEM_PNG);

        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("arquivo"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("O conteudo do arquivo nao corresponde a uma imagem JPG/PNG/WEBP valida."));

        mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void enviar_arquivoVazio_devolve400() throws Exception {
        Long id = inserirProdutoSemImagem("Cisto");
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "vazio.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[0]);

        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("arquivo"))
                .andExpect(jsonPath("$.error.details[0].message").value("Envie um arquivo de imagem."));
    }

    @Test
    void enviar_semParteArquivo_devolve400() throws Exception {
        Long id = inserirProdutoSemImagem("Freesia");

        // Sem nenhuma parte 'arquivo' -> required=false -> null -> validacao amigavel.
        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].message").value("Envie um arquivo de imagem."));
    }

    // ---- CA-6: upload em produto inexistente -> 404 ------------------------------------------

    @Test
    void enviar_produtoInexistente_devolve404() throws Exception {
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.jpg", MediaType.IMAGE_JPEG_VALUE, IMAGEM_JPEG);

        mockMvc.perform(multipart("/api/v1/produtos/999999/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
