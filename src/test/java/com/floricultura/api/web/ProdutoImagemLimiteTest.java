package com.floricultura.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.floricultura.api.service.JwtService;
import java.util.UUID;
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
 * Fatia do LIMITE DE TAMANHO do upload (M3/T-M3-3, CA-5) — separada de {@code ProdutoImagemApiTest}
 * porque precisa de {@code app.upload.imagem.max-bytes} <b>reduzido</b> (o {@code @Value} e lido na
 * construcao do {@code ProdutoImagemService}, entao o override precisa valer para todo o contexto).
 *
 * <p>Com o limite de negocio em <b>4 bytes</b>, um JPEG valido de 7 bytes — que passaria folgado no
 * default de 5 MB — e rejeitado com 400: prova que (a) {@code > limite → 400} com a mensagem exata e
 * (b) a env {@code APP_UPLOAD_IMAGEM_MAX_BYTES} <b>manda</b> no limite efetivo (o novo valor vale). O
 * teto do container (> 6MB) fica como divida P2 (smoke manual — MockMvc nao enforca).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProdutoImagemLimiteTest {

    /** JPEG valido (magic FF D8 FF) de 7 bytes — acima do limite reduzido de 4 bytes. */
    private static final byte[] JPEG_7_BYTES =
            new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03, 0x04};

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-produtoimglimite-0123456789ab");
        // CA-5: limite de negocio reduzido para provar que a env manda no limite efetivo.
        registry.add("app.upload.imagem.max-bytes", () -> "4");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void enviar_acimaDoLimiteReduzido_devolve400() throws Exception {
        jdbc.update("DELETE FROM movimentacao_estoque");
        jdbc.update("DELETE FROM produto");
        jdbc.update("DELETE FROM usuario");
        Long adminId = jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES ('Admin', 'admin-limite@floricultura.local', ?, 'ADMIN', true, false) "
                        + "RETURNING id",
                Long.class, ENCODER.encode(UUID.randomUUID().toString()));
        Long produtoId = jdbc.queryForObject(
                "INSERT INTO produto (nome, unidade_medida) VALUES ('Rosa', 'un') RETURNING id",
                Long.class);
        String adminBearer = "Bearer " + jwtService.gerarToken(adminId);

        MockMultipartFile parte =
                new MockMultipartFile("arquivo", "foto.jpg", MediaType.IMAGE_JPEG_VALUE, JPEG_7_BYTES);

        mockMvc.perform(multipart("/api/v1/produtos/" + produtoId + "/imagem").file(parte)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("arquivo"))
                .andExpect(jsonPath("$.error.details[0].message")
                        .value("Imagem excede o tamanho maximo de 5 MB."));

        // Nada gravado: o GET do binario continua 404.
        mockMvc.perform(get("/api/v1/produtos/" + produtoId + "/imagem")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound());
    }
}
