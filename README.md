# floricultura-api

API REST de **gestão interna de uma floricultura** — o back-end que controla usuários, produtos,
estoque e imagens do catálogo. Construída em **Java 21 + Spring Boot 4**, com **PostgreSQL** e
migrações versionadas via **Flyway**.

> Front-end (SPA Angular) no repositório irmão `floricultura-web`. Os dois se comunicam pelo
> contrato REST descrito abaixo (envelope padronizado sobre `/api/v1`).

## A ideia do projeto

Ferramenta de uso **interno** (não é loja/e-commerce) para o dia a dia da floricultura:

- **Autenticação e perfis (RBAC):** login por JWT; dois papéis — **ADMIN** (gestão completa,
  inclusive de usuários) e **USER** (operação). A permissão é lida do banco a cada requisição,
  não "congelada" no token.
- **Produtos & catálogo:** CRUD de produtos com unidade de medida, estoque mínimo, preço
  (opcional) e **imagem** — que pode ser um **link externo** ou um **binário enviado do
  dispositivo e guardado no próprio PostgreSQL**.
- **Estoque como razão imutável (ledger):** o estoque nunca é editado "na mão". Toda mudança
  (ENTRADA/SAÍDA/AJUSTE) entra como um lançamento no histórico; saída maior que o disponível é
  bloqueada (o estoque nunca fica negativo).
- **Mobile-first:** ~90% do uso é no celular — o contrato e o front foram desenhados com isso em
  mente (paginação server-side, payloads enxutos, binário de imagem fora das listas).

## Tecnologias

| Camada | Tecnologia |
|---|---|
| Linguagem / runtime | **Java 21** |
| Framework | **Spring Boot 4.1.1** (Web MVC, Data JPA, Validation, Actuator) |
| Segurança | **Spring Security 7** + **JWT HS256** (Nimbus / `spring-security-oauth2-jose`) |
| Banco de dados | **PostgreSQL** |
| Migrações | **Flyway 12** (`flyway-core` + `flyway-database-postgresql`) |
| Documentação da API | **OpenAPI / Swagger UI** (springdoc 3.1.0) |
| Testes | **JUnit 5**, **Spring Boot Test / MockMvc**, **Testcontainers** (PostgreSQL de descarte) |
| Build | **Maven** (wrapper `mvnw` versionado) |

Versões das dependências são governadas pelo BOM `spring-boot-dependencies:4.1.1` (sem pin manual,
exceto o springdoc). Hibernate roda com `ddl-auto=validate` — **quem cria/altera schema é o Flyway**.

## Arquitetura

### Contrato REST — envelope padronizado

Toda resposta JSON sob `/api/v1/**` trafega no mesmo envelope:

```json
{ "success": true, "data": { ... }, "error": null,
  "timestamp": "2026-09-03T12:00:00Z", "path": "/api/v1/produtos" }
```

Em erro, `success:false` e `error` traz `code` + `message` (+ `details` em falha de validação). Os
códigos mapeiam para HTTP: `VALIDATION_ERROR`→400, `UNAUTHORIZED`→401, `FORBIDDEN`→403,
`NOT_FOUND`→404, `INTERNAL_ERROR`→500. **Exceção deliberada:** `GET /produtos/{id}/imagem` devolve o
**binário cru** (não o envelope), com seus próprios headers de `Content-Type`/`Cache-Control`.

### Camadas (arquitetura em fatias por responsabilidade)

```
src/main/java/com/floricultura/api/
├─ config/       # SecurityConfig, JwtAuthenticationFilter, CorsConfig, OpenApiConfig,
│                #   RestAuthenticationEntryPoint (401), RestAccessDeniedHandler (403)
├─ domain/       # entidades JPA + factories: Usuario, Produto, MovimentacaoEstoque
├─ repository/   # Spring Data JPA: Usuario/Produto/MovimentacaoRepository
│                #   + ProdutoImagemProjection (lê o bytea da imagem por query nativa)
├─ service/      # regras de negócio: Auth, Jwt, Usuario, Produto, Movimentacao,
│                #   ProdutoImagem (+ exceções de domínio tipadas)
└─ web/          # controllers REST (Auth, Usuario, Produto, Movimentacao, ProdutoImagem,
   ├─ dto/       #   HealthCheck), DTOs de request/response e paginação
   ├─ error/     #   GlobalExceptionHandler + ErrorCode (traduz exceções → envelope)
   └─ response/  #   ApiResponse / ApiError / FieldErrorItem (o envelope)
```

### Decisões de arquitetura relevantes

- **RBAC lido do banco por request** (não do token): revogar/alterar papel tem efeito imediato.
- **Estoque via ledger imutável** (`movimentacao_estoque`): produto nasce com estoque 0; toda
  variação passa por `POST /produtos/{id}/movimentacoes`, com lock pessimista e bloqueio de saldo
  negativo.
- **Imagem no banco (`bytea`), sem pesar as leituras:** a entidade `Produto` **não mapeia** a coluna
  binária — `GET /produtos` e `GET /produtos/{id}` nunca carregam megabytes. O binário só é lido pelo
  endpoint dedicado, via projeção nativa. Upload valida tipo (JPG/PNG/WEBP), tamanho (≤5 MB, ajustável
  por env) e **anti-spoofing por _magic bytes_** (não confia na extensão nem no content-type declarado).
- **Segredos nunca no repositório:** datasource e JWT leem apenas variáveis de ambiente; a aplicação
  **falha ao subir** sem `APP_JWT_SECRET`.

### Modelo de dados (migrações Flyway)

| Versão | Conteúdo |
|---|---|
| `V1` | baseline |
| `V2` | autenticação: senha provisória + seed do 1º admin (sem segredo no repo) |
| `V3` | produto: preço opcional + `CHECK` de unidade de medida |
| `V4` | movimentação de estoque imutável (permite cascade) |
| `V5` | imagem do produto no banco: `imagem BYTEA` + `imagem_content_type`/`imagem_filename` + CHECKs |

## Endpoints (visão geral)

- **Auth:** `POST /api/v1/auth/login`, `GET /api/v1/auth/me`, troca/redefinição de senha.
- **Usuários (ADMIN):** CRUD + listagem paginada.
- **Produtos:** CRUD + listagem paginada com filtro; `POST/GET/DELETE /produtos/{id}/imagem`.
- **Estoque:** `POST /produtos/{id}/movimentacoes` (ENTRADA/SAÍDA/AJUSTE) + histórico.
- **Infra:** `GET /api/v1/health-check`, Actuator (`/actuator/health`, `/actuator/info`).

Documentação interativa: **Swagger UI** em `/swagger-ui.html` (spec em `/v3/api-docs`).

## Como rodar (dev)

Pré-requisitos: **JDK 21**, **PostgreSQL** acessível e as variáveis de ambiente abaixo exportadas
(nunca commitadas). O profile default é `dev`.

```bash
# Variáveis mínimas (exemplos — use valores locais, não commite)
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/floricultura
export SPRING_DATASOURCE_USERNAME=floricultura
export SPRING_DATASOURCE_PASSWORD=...            # segredo local
export APP_JWT_SECRET=...                        # >= 32 bytes; obrigatório (o app falha sem ele)
# opcionais:
export APP_JWT_TTL=3600                           # validade do token, em segundos
export APP_UPLOAD_IMAGEM_MAX_BYTES=5242880        # limite de upload (default 5 MB)

./mvnw spring-boot:run        # sobe a API em http://localhost:8080 (Flyway aplica V1..V5 na subida)
./mvnw clean verify           # build + suíte completa (JUnit 5 + Testcontainers)
```

> No profile `dev`, além das migrações comuns, é carregado um seed **dev-only** que cria um admin de
> desenvolvimento (`db/migration-dev`) — inexistente em produção. Em produção, defina
> `SPRING_PROFILES_ACTIVE=prod`.

### Variáveis de ambiente

| Variável | Obrigatória | Descrição |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | sim | conexão PostgreSQL |
| `APP_JWT_SECRET` | sim | segredo HS256 (≥ 32 bytes) — sem ele o app não sobe |
| `APP_JWT_TTL` | não | validade do token em segundos (default `3600`) |
| `APP_UPLOAD_IMAGEM_MAX_BYTES` | não | limite do upload de imagem (default `5242880` = 5 MB) |
| `SPRING_PROFILES_ACTIVE` | não | `dev` (default) ou `prod` |

## Testes

```bash
./mvnw clean verify
```

A suíte usa **Testcontainers** para subir um PostgreSQL real e efêmero, exercitando migrações Flyway,
segurança/JWT e os fluxos de negócio de ponta a ponta (sem mocar o banco onde há comportamento real).
