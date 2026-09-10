package com.floricultura.api.web;

import static org.hamcrest.Matchers.hasItems;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integracao dos filtros/ordenacao de contato do M6 (T-M6-08a, CA-24/CA-25 — SPEC-M6 §3.7) contra um
 * PostgreSQL de DESCARTE (Testcontainers postgres:16), com {@code ClienteController}/
 * {@code FornecedorController} e filtro JWT reais. O contrato e <b>identico</b> nos dois cadastros, entao
 * a classe roda a mesma bateria nos dois blocos (clientes e fornecedores).
 *
 * <p>Prova: tri-estado de {@code comTelefone}/{@code comEmail} (ausente/true/false), string <b>vazia</b>
 * contando como "sem" (R25), combinacao <b>E</b> entre filtros, ordenacao por {@code telefone}/
 * {@code email} com os "sem" no <b>fim nas duas direcoes</b> e desempate {@code nome ASC},
 * {@code ordenarPor} fora do conjunto → 400, default {@code nome asc}, e o Swagger das duas rotas.
 *
 * <p>Leitura vale para USER (FC-07) — todos os casos usam token USER. Dados ficticios (LGPD):
 * "Ana Bromelias" / {@code (11) 90000-000X} / {@code @exemplo.com.br}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ContatoFiltroApiTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static final String CLIENTES = "/api/v1/clientes";

    private static final String FORNECEDORES = "/api/v1/fornecedores";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-nao-segredo-contatofiltro-0123456789ab");
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
        // TRUNCATE do ledger ANTES de apagar contato: com linhas no ledger o DELETE dispararia o
        // cascade SET NULL (UPDATE) barrado pela trigger de imutabilidade (padrao do ClienteApiTest).
        jdbc.update("TRUNCATE TABLE movimentacao_estoque");
        jdbc.update("DELETE FROM cliente");
        jdbc.update("DELETE FROM fornecedor");
        jdbc.update("DELETE FROM usuario");
        Long userId = jdbc.queryForObject(
                "INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria) "
                        + "VALUES (?, ?, ?, 'USER', true, false) RETURNING id",
                Long.class, "Nome USER", "user-contato@floricultura.local",
                ENCODER.encode(UUID.randomUUID().toString()));
        userBearer = "Bearer " + jwtService.gerarToken(userId);
    }

    // ---- Fixtures (LGPD: nomes/telefones/e-mails ficticios) -----------------------------------

    /** C1 (tel + email) · C2 (tel, email NULO) · C3 (sem tel, email VAZIO) — fixture do CA-24. */
    private void seedTrio(String tabela) {
        inserir(tabela, "Ana Bromelias", "(11) 90000-0001", "ana@exemplo.com.br");
        inserir(tabela, "Bruno Cactos", "(11) 90000-0002", null);
        inserir(tabela, "Carla Dalias", null, "");
    }

    /** Trio + um 4o contato com telefone e e-mail, para a ordenacao do CA-25 ter 2 preenchidos. */
    private void seedQuarteto(String tabela) {
        seedTrio(tabela);
        inserir(tabela, "Zeta Jardins", "(11) 90000-0004", "zeta@exemplo.com.br");
    }

    private void inserir(String tabela, String nome, String telefone, String email) {
        jdbc.update("INSERT INTO " + tabela + " (nome, telefone, email) VALUES (?, ?, ?)",
                nome, telefone, email);
    }

    /** GET autenticado (USER) que assere {@code totalElementos} e a SEQUENCIA exata de nomes. */
    private void esperaNomes(String rota, String query, String... nomes) throws Exception {
        ResultActions resultado = mockMvc.perform(get(rota + "?" + query)
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(nomes.length));
        for (int i = 0; i < nomes.length; i++) {
            resultado.andExpect(jsonPath("$.data.conteudo[" + i + "].nome").value(nomes[i]));
        }
    }

    private void esperaOrdenacaoInvalida(String rota, String query, String campo) throws Exception {
        mockMvc.perform(get(rota + "?" + query).header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value(campo));
    }

    // ---- CA-24: tri-estado de comTelefone/comEmail (clientes) ---------------------------------

    @Test
    void clientes_semFiltro_devolveTodos() throws Exception {
        seedTrio("cliente");

        esperaNomes(CLIENTES, "pagina=0&tamanho=20",
                "Ana Bromelias", "Bruno Cactos", "Carla Dalias");
    }

    @Test
    void clientes_comTelefone_triEstado() throws Exception {
        seedTrio("cliente");

        esperaNomes(CLIENTES, "comTelefone=true", "Ana Bromelias", "Bruno Cactos");
        esperaNomes(CLIENTES, "comTelefone=false", "Carla Dalias");
    }

    @Test
    void clientes_comEmail_stringVaziaContaComoSem() throws Exception {
        seedTrio("cliente");

        // C2 tem email NULO e C3 tem email VAZIO — os dois sao "sem e-mail" (R25).
        esperaNomes(CLIENTES, "comEmail=false", "Bruno Cactos", "Carla Dalias");
        esperaNomes(CLIENTES, "comEmail=true", "Ana Bromelias");
    }

    @Test
    void clientes_filtrosCombinamComE_entreSiEComNome() throws Exception {
        seedTrio("cliente");

        esperaNomes(CLIENTES, "comTelefone=true&comEmail=false", "Bruno Cactos");
        // "ana" casa so o C1 (ILIKE); somado ao comTelefone=true segue 1 — E entre as dimensoes.
        esperaNomes(CLIENTES, "nome=ANA&comTelefone=true", "Ana Bromelias");
        esperaNomes(CLIENTES, "nome=ANA&comTelefone=false");
    }

    // ---- CA-25: ordenacao com "sem" no fim nas duas direcoes + 400 (clientes) -----------------

    @Test
    void clientes_ordenarPorEmailDesc_semEmailVaoParaOFimComDesempateNome() throws Exception {
        seedQuarteto("cliente");

        // zeta@ > ana@ no desc; os dois "sem e-mail" (nulo e vazio) fecham a lista, por nome ASC.
        esperaNomes(CLIENTES, "ordenarPor=email&direcao=desc",
                "Zeta Jardins", "Ana Bromelias", "Bruno Cactos", "Carla Dalias");
    }

    @Test
    void clientes_ordenarPorEmailAsc_semEmailTambemVaoParaOFim() throws Exception {
        seedQuarteto("cliente");

        esperaNomes(CLIENTES, "ordenarPor=email&direcao=asc",
                "Ana Bromelias", "Zeta Jardins", "Bruno Cactos", "Carla Dalias");
    }

    @Test
    void clientes_ordenarPorTelefoneDesc_semTelefoneVaiParaOFim() throws Exception {
        seedQuarteto("cliente");

        esperaNomes(CLIENTES, "ordenarPor=telefone&direcao=desc",
                "Zeta Jardins", "Bruno Cactos", "Ana Bromelias", "Carla Dalias");
    }

    @Test
    void clientes_semOrdenacao_usaNomeAsc_eNomeDescInverte() throws Exception {
        seedQuarteto("cliente");

        esperaNomes(CLIENTES, "pagina=0",
                "Ana Bromelias", "Bruno Cactos", "Carla Dalias", "Zeta Jardins");
        esperaNomes(CLIENTES, "ordenarPor=nome&direcao=desc",
                "Zeta Jardins", "Carla Dalias", "Bruno Cactos", "Ana Bromelias");
    }

    @Test
    void clientes_ordenacaoForaDoContrato_devolve400() throws Exception {
        esperaOrdenacaoInvalida(CLIENTES, "ordenarPor=observacoes", "ordenarPor");
        esperaOrdenacaoInvalida(CLIENTES, "ordenarPor=email&direcao=cima", "direcao");
    }

    @Test
    void clientes_filtroComPaginacao_totalRefleteOFiltro() throws Exception {
        seedQuarteto("cliente");

        // 3 casam comTelefone=true; com tamanho=2 a 1a pagina traz 2 e o total continua 3 (P5).
        mockMvc.perform(get(CLIENTES + "?comTelefone=true&tamanho=2&pagina=0")
                        .header(HttpHeaders.AUTHORIZATION, userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElementos").value(3))
                .andExpect(jsonPath("$.data.totalPaginas").value(2))
                .andExpect(jsonPath("$.data.conteudo.length()").value(2));
    }

    // ---- CA-24/CA-25 espelhados em /fornecedores (contrato identico — D3) ---------------------

    @Test
    void fornecedores_comTelefoneEComEmail_triEstadoEStringVazia() throws Exception {
        seedTrio("fornecedor");

        esperaNomes(FORNECEDORES, "pagina=0&tamanho=20",
                "Ana Bromelias", "Bruno Cactos", "Carla Dalias");
        esperaNomes(FORNECEDORES, "comTelefone=true", "Ana Bromelias", "Bruno Cactos");
        esperaNomes(FORNECEDORES, "comTelefone=false", "Carla Dalias");
        esperaNomes(FORNECEDORES, "comEmail=false", "Bruno Cactos", "Carla Dalias");
        esperaNomes(FORNECEDORES, "comTelefone=true&comEmail=false", "Bruno Cactos");
    }

    @Test
    void fornecedores_ordenarPorEmail_semEmailVaoParaOFimNasDuasDirecoes() throws Exception {
        seedQuarteto("fornecedor");

        esperaNomes(FORNECEDORES, "ordenarPor=email&direcao=desc",
                "Zeta Jardins", "Ana Bromelias", "Bruno Cactos", "Carla Dalias");
        esperaNomes(FORNECEDORES, "ordenarPor=email&direcao=asc",
                "Ana Bromelias", "Zeta Jardins", "Bruno Cactos", "Carla Dalias");
    }

    @Test
    void fornecedores_ordenacaoForaDoContrato_devolve400() throws Exception {
        esperaOrdenacaoInvalida(FORNECEDORES, "ordenarPor=observacoes", "ordenarPor");
        esperaOrdenacaoInvalida(FORNECEDORES, "ordenarPor=nome&direcao=cima", "direcao");
    }

    // ---- Swagger das duas rotas alteradas (exigencia permanente do dono) ----------------------

    @Test
    void swagger_documentaFiltrosEOrdenacaoNasDuasRotas() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/clientes'].get.summary").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/clientes'].get.parameters[*].name",
                        hasItems("comTelefone", "comEmail", "ordenarPor", "direcao")))
                .andExpect(jsonPath("$.paths['/api/v1/fornecedores'].get.summary").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/fornecedores'].get.parameters[*].name",
                        hasItems("comTelefone", "comEmail", "ordenarPor", "direcao")));
    }
}
