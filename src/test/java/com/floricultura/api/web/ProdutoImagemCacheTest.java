package com.floricultura.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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
 * Integracao de CACHE/ETag/304 do {@code GET .../imagem} (M5.2/T-M5.2-4, CA-C6 — SPEC-M5.2 §3.6) contra
 * PostgreSQL de DESCARTE (Testcontainers). Prova: {@code Cache-Control: public, max-age=31536000,
 * immutable} + {@code ETag} forte em toda variante; {@code If-None-Match} casando → <b>304</b> sem
 * corpo; apos reupload (novo {@code atualizado_em}) o ETag muda e o mesmo {@code If-None-Match} volta 200.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoImagemCacheTest {

    private static final String CACHE_ESPERADO = "public, max-age=31536000, immutable";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-imagemcache-0123456789abcd");
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
        adminBearer = "Bearer " + jwtService.gerarToken(inserirUsuario("admin-cache@floricultura.local", "ADMIN"));
        userBearer = "Bearer " + jwtService.gerarToken(inserirUsuario("user-cache@floricultura.local", "USER"));
    }

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

    private static byte[] png(int largura, int altura) throws IOException {
        BufferedImage img = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < altura; y++) {
            for (int x = 0; x < largura; x++) {
                int r = (x * 255) / Math.max(1, largura - 1);
                int b = (y * 255) / Math.max(1, altura - 1);
                img.setRGB(x, y, (r << 16) | (((r + b) / 2) << 8) | b);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private void upload(Long id, int w, int h) throws Exception {
        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.png", "image/png", png(w, h));
        mockMvc.perform(multipart("/api/v1/produtos/" + id + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk());
    }

    @Test
    void get_trazCacheImutavelEEtag_condicionalDevolve304_eMudaAposReupload() throws Exception {
        Long id = inserirProdutoSemImagem("Rosa");
        upload(id, 400, 300);

        // 1) GET normal: Cache-Control imutavel de 1 ano + ETag forte.
        MvcResult r1 = mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem?tamanho=thumb")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CACHE_ESPERADO))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();
        String etag = r1.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotBlank();

        // 2) If-None-Match casando -> 304 sem corpo.
        MvcResult r304 = mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem?tamanho=thumb")
                        .header(HttpHeaders.AUTHORIZATION, userBearer)
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CACHE_ESPERADO))
                .andReturn();
        assertThat(r304.getResponse().getContentAsByteArray()).as("304 sem corpo").isEmpty();

        // 3) Reupload -> novo atualizado_em -> ETag muda -> o If-None-Match antigo volta 200.
        Thread.sleep(10); // garante atualizado_em em outro epoch-ms
        upload(id, 400, 300);
        MvcResult r2 = mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem?tamanho=thumb")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andReturn();
        String etag2 = r2.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag2).isNotBlank().isNotEqualTo(etag);

        MvcResult rStale = mockMvc.perform(get("/api/v1/produtos/" + id + "/imagem?tamanho=thumb")
                        .header(HttpHeaders.AUTHORIZATION, userBearer)
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(rStale.getResponse().getContentAsByteArray())
                .as("ETag antigo nao casa o novo -> 200 com corpo").isNotEmpty();
    }
}
