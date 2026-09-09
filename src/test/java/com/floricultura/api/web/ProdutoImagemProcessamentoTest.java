package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integracao do PIPELINE de imagem + servico de variantes (M5.2/T-M5.2-3+4, CA-C1/C2/C4/C5/C8/C9 +
 * C-R7) contra um PostgreSQL de DESCARTE (Testcontainers postgres:16), com controller/servico/security
 * reais. Prova: upload gera 3 variantes com resize+no-upscale (CA-C1) e encolhimento relevante (CA-C2);
 * {@code ?tamanho=} serve a variante certa e {@code xpto}→400 {@code field=tamanho} (CA-C4); coexistencia
 * com legado 1-imagem via fallback (CA-C5); decode corrompido→400 (CA-C8); DELETE limpa variantes e o
 * GET seguinte da 404 (CA-C9); upload {@code .webp} REAL e decodificado pelo reader TwelveMonkeys (C-R7).
 * Fixtures determinISTICOS (gradiente suave). Segredos de teste sao NAO-segredos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoImagemProcessamentoTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /** Fixture WebP REAL gerado pelo writer (usefulness) em @BeforeAll; null se o nativo nao carregar. */
    private static byte[] webpFixture;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-processamento-0123456789abcd");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String userBearer;

    @BeforeAll
    static void gerarWebpFixture() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (ImageIO.write(gradiente(60, 40), "webp", baos) && baos.size() > 0) {
                webpFixture = baos.toByteArray();
            }
        } catch (Throwable t) {
            webpFixture = null; // writer indisponivel — o caso .webp e ignorado (assumeTrue)
        }
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        Long adminId = inserirUsuario("admin-proc@floricultura.local", "ADMIN");
        Long userId = inserirUsuario("user-proc@floricultura.local", "USER");
        adminBearer = "Bearer " + jwtService.gerarToken(adminId);
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    // ----- helpers -----

    private Long inserirUsuario(String email, String role) {
        return jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, ?, true, false) RETURNING id",
                Long.class, "Nome " + role, email,
                ENCODER.encode(UUID.randomUUID().toString()), role);
    }

    private Long inserirProdutoSemImagem(String nome) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES (?, 'un') RETURNING id",
                Long.class, nome);
    }

    /** Insere um produto LEGADO (original direto em produto.imagem, SEM variantes) com bytes reais. */
    private Long inserirProdutoLegado(String nome, byte[] png) {
        return jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida, imagem, imagem_content_type, imagem_filename) "
                        + "VALUES (?, 'un', ?, 'image/png', 'legado.png') RETURNING id",
                Long.class, nome, png);
    }

    private int contarVariantes(Long produtoId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM produto_imagem_variante WHERE produto_id = ?",
                Integer.class, produtoId);
    }

    /** Gradiente suave determinISTICO (armadilha 6). */
    private static BufferedImage gradiente(int largura, int altura) {
        BufferedImage img = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < altura; y++) {
            for (int x = 0; x < largura; x++) {
                int r = (x * 255) / Math.max(1, largura - 1);
                int b = (y * 255) / Math.max(1, altura - 1);
                img.setRGB(x, y, (r << 16) | (((r + b) / 2) << 8) | b);
            }
        }
        return img;
    }

    private static byte[] png(int largura, int altura) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(gradiente(largura, altura), "png", baos);
        return baos.toByteArray();
    }

    private byte[] uploadEServir(Long id, byte[] png, String tamanhoQuery) throws Exception {
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.png", MediaType.IMAGE_PNG_VALUE, png);
        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temImagem").value(true));
        String url = "/api/v1/produtos/" + id + "/imagem"
                + (tamanhoQuery == null ? "" : "?tamanho=" + tamanhoQuery);
        return mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static int maxLado(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(img).as("bytes servidos devem ser decodaveis").isNotNull();
        return Math.max(img.getWidth(), img.getHeight());
    }

    // ----- CA-C1: 3 variantes com resize + no-upscale -----

    @Test
    void upload_geraTresVariantes_resizeENoUpscale() throws Exception {
        Long id = inserirProdutoSemImagem("Rosa");
        byte[] entrada = png(1920, 1280);

        assertThat(maxLado(uploadEServir(id, entrada, "original"))).isEqualTo(1280);
        assertThat(maxLado(servir(id, "medio"))).isEqualTo(800);
        assertThat(maxLado(servir(id, "thumb"))).isEqualTo(200);
        assertThat(contarVariantes(id)).as("2 variantes: thumb + medio").isEqualTo(2);

        // no-upscale: 150x100 < todos os alvos -> original permanece 150x100.
        Long p2 = inserirProdutoSemImagem("Lirio");
        assertThat(maxLado(uploadEServir(p2, png(150, 100), "original"))).isEqualTo(150);
    }

    // ----- CA-C2: encolhimento relevante -----

    @Test
    void upload_encolhimentoRelevante() throws Exception {
        Long id = inserirProdutoSemImagem("Cravo");
        byte[] entrada = png(1920, 1280);
        byte[] original = uploadEServir(id, entrada, "original");
        byte[] thumb = servir(id, "thumb");

        assertThat(original.length).isLessThan(307200).isLessThan(entrada.length);
        assertThat(thumb.length).isLessThan(30720);
    }

    // ----- CA-C4: query param tamanho -----

    @Test
    void get_tamanhoAusente_serveOriginal() throws Exception {
        Long id = inserirProdutoSemImagem("Tulipa");
        assertThat(maxLado(uploadEServir(id, png(1920, 1280), null))).isEqualTo(1280);
    }

    @Test
    void get_tamanhoInvalido_devolve400FieldTamanho() throws Exception {
        Long id = inserirProdutoSemImagem("Dalia");
        uploadEServir(id, png(400, 300), "original");

        mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem?tamanho=xpto")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("tamanho"));
    }

    // ----- CA-C5: coexistencia com legado (fallback ao original) -----

    @Test
    void legadoSemVariante_serveOriginalPorFallback_eNovoUploadCoexiste() throws Exception {
        byte[] legadoPng = png(30, 20);
        Long legado = inserirProdutoLegado("Margarida", legadoPng);
        assertThat(contarVariantes(legado)).isZero();

        // thumb/medio/original: todos servem o ORIGINAL legado (image/png), nunca 404 por variante ausente.
        for (String t : new String[] {"thumb", "medio", "original"}) {
            MvcResult res = mockMvc.perform(get("/api/v1/produtos/" + legado + "/imagem?tamanho=" + t)
                            .header(HttpHeaders.AUTHORIZATION, userBearer))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE))
                    .andReturn();
            assertThat(res.getResponse().getContentAsByteArray())
                    .as("fallback serve os bytes do original legado (" + t + ")").isEqualTo(legadoPng);
        }

        // Produto com upload NOVO tem variantes reais — coexiste com o legado.
        Long novo = inserirProdutoSemImagem("Girassol");
        uploadEServir(novo, png(1920, 1280), "original");
        assertThat(contarVariantes(novo)).isEqualTo(2);
        assertThat(contarVariantes(legado)).as("legado segue sem variantes").isZero();
    }

    // ----- CA-C8: decode corrompido -> 400 -----

    @Test
    void upload_magicValidoConteudoCorrompido_devolve400() throws Exception {
        Long id = inserirProdutoSemImagem("Azaleia");
        byte[] corrompido = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7};
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "fake.jpg", MediaType.IMAGE_JPEG_VALUE, corrompido);

        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("arquivo"));

        // Nada gravado.
        mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound());
    }

    // ----- CA-C9: DELETE limpa variantes -----

    @Test
    void delete_limpaVariantes_eGetSeguinte404() throws Exception {
        Long id = inserirProdutoSemImagem("Violeta");
        uploadEServir(id, png(800, 600), "original");
        assertThat(contarVariantes(id)).isEqualTo(2);

        mockMvc.perform(delete("/api/v1/produtos/" + id + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        assertThat(contarVariantes(id)).as("variantes apagadas na mesma transacao").isZero();
        mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem?tamanho=thumb")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound());
    }

    // ----- C-R7: upload .webp REAL decodificado pelo reader TwelveMonkeys -----

    @Test
    void upload_webpReal_decodificaEServeVariante() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(webpFixture != null,
                "writer WebP indisponivel p/ gerar o fixture .webp neste host");
        Long id = inserirProdutoSemImagem("Petunia");
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.webp", "image/webp", webpFixture);

        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temImagem").value(true));

        // O reader puro-Java decodou a entrada .webp; a variante servida e decodavel, 60x40 (no-upscale).
        assertThat(maxLado(servir(id, "original"))).isEqualTo(60);
    }

    private byte[] servir(Long id, String tamanho) throws Exception {
        return mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem?tamanho=" + tamanho)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static org.springframework.test.web.servlet.result.HeaderResultMatchers header() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.header();
    }
}
