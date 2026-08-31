# Arquitetura — Floricultura

Documento de referência da fundação técnica (milestone **M0**). Descreve as **camadas do back-end**,
a **estrutura do front-end** e o **contrato REST base** (envelope de resposta e versionamento).

- **Contrato / fonte de verdade:** [`SPEC-M0`](../../../squad/specs/SPEC-M0.md) — o contrato do §3 é a
  definição de pronto; divergência é defeito.
- **Decisões (ADR-lite):** [`DECISOES.md`](../../../squad/DECISOES.md) — em especial
  **AD-SQ-3** (Flyway), **AD-SQ-4** (contrato REST/envelope), **AD-SQ-5** (estrutura de diretórios),
  **AD-SQ-6** (security scaffold / JWT só em M1), **AD-SQ-9** (profiles + `.env`),
  **AD-SQ-10** (versões), **AD-SQ-11** (testes smoke).
- **Fatos canônicos:** [`contexto/_INDICE.md`](../../../squad/contexto/_INDICE.md).

> Estado nesta entrega (**T-M0-1 — scaffold back + config base**): projeto Spring Boot que **compila e
> sobe**, com estrutura de camadas, profiles e config base. O contrato REST executável
> (`ApiResponse`, `GlobalExceptionHandler`, `HealthCheckController`, `SecurityConfig`, CORS) entra em
> **T-M0-2**; a migração Flyway `V1__baseline.sql` entra em **T-M0-3**. As seções abaixo descrevem o
> alvo completo do M0 para orientar essas tarefas.

## Stack (AD-SQ-10)

- **Back:** Java 21 (LTS) + **Spring Boot 4.1.1** · Maven (wrapper `./mvnw`).
- **Banco:** PostgreSQL · migrações versionadas com **Flyway** 12.4.0 (AD-SQ-3, gerenciado pelo BOM).
- **Front:** Angular 20.x + Angular Material 20.x (repo `floricultura-web`).

> **Nota de versão (T-M0-1 / AD-SQ-13):** a premissa original era Boot 3.x, mas o Spring Initializr
> hoje só serve a linha **4.x** (rejeita 3.x com HTTP 400). No gate da Onda 1 o humano aprovou **migrar
> para Spring Boot 4.1.1** já no scaffold (custo mínimo com o codebase ainda vazio; evita o upgrade
> 3→4 e o retrabalho de Spring Security 6→7 na Onda 2/M1). **Java 21 (LTS) mantido.** Todas as versões
> de dependência vêm do BOM `spring-boot-dependencies:4.1.1` — **sem pin manual**. No Boot 4 o starter
> web chama-se `spring-boot-starter-webmvc` e as classes de auto-config mudaram de pacote (módulos
> próprios). O Maven Wrapper é `only-script` (baixa Maven 3.9.16).

## Back-end — organização em camadas (layer-based, AD-SQ-5 / SPEC §3.7)

Pacote raiz: `com.floricultura.api`.

```
com.floricultura.api
├─ FloriculturaApiApplication.java   # ponto de entrada (@SpringBootApplication)
├─ config/         # SecurityConfig, CorsConfig, ...            (vazio em M0-1; T-M0-2)
├─ web/            # controllers REST (ex.: HealthCheckController) (T-M0-2)
├─ web/response/   # ApiResponse, ApiError, FieldErrorItem        (T-M0-2)
├─ web/error/      # GlobalExceptionHandler (@RestControllerAdvice), ErrorCode (T-M0-2)
├─ service/        # regra de negócio                             (vazio em M0)
├─ repository/     # Spring Data                                  (vazio em M0)
└─ domain/         # entidades JPA                                (vazio em M0; schema via Flyway)
```

```
src/main/resources/
├─ application.yml          # config comum (datasource por env, JPA validate, Actuator, Flyway)
├─ application-dev.yml      # profile dev (health show-details=always, CORS localhost:4200)
├─ application-prod.yml     # profile prod (tudo sensível vem de env vars/secrets do host)
└─ db/migration/            # V1__baseline.sql entra em T-M0-3 (7 tabelas + trigger + índices)
```

**Fluxo de uma requisição (alvo M0):** `web/` (controller) → `service/` (regra) → `repository/`
(Spring Data) → `domain/` (entidades) ↔ PostgreSQL. Respostas sempre encapsuladas no envelope
(`web/response/`); erros centralizados em `web/error/GlobalExceptionHandler`.

### Configuração e segredos (AD-SQ-9 / SPEC §3.3, §9)

- **Profiles:** `application.yml` (comum) + `application-dev.yml` + `application-prod.yml`. Default local
  = `dev`; em produção defina `SPRING_PROFILES_ACTIVE=prod`.
- **Datasource por variável de ambiente:** `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
  `SPRING_DATASOURCE_PASSWORD`. **Nenhum segredo é versionado** (FC-15). Em **dev**, o desenvolvedor
  **exporta** essas variáveis no ambiente/IDE (run config, plugin EnvFile ou `export` no shell); o
  arquivo [`.env.example`](../.env.example) (sem valores, versionado) serve de modelo e o `.env` real
  fica gitignored. Em **prod**, as variáveis vêm dos secrets/env vars do host. Não há lib de auto-load
  de `.env` no scaffold: `me.paulschwarz:spring-dotenv` foi **removida** (AD-SQ-13 — o jar 5.1.0 não
  auto-registra sob Spring Framework 7 / Boot 4, então não garantia o carregamento). O `application*.yml`
  é agnóstico de versão: continua lendo `${SPRING_DATASOURCE_*}` do ambiente, sem alteração.
- **JPA × Flyway:** Flyway é o dono do schema; Hibernate roda com `ddl-auto=validate` (nunca cria/altera).
- **Actuator:** expõe apenas `health` e `info`; `health.show-details=always` só em `dev`.

### Segurança (scaffold em M0 — AD-SQ-6)

M0 adiciona `spring-boot-starter-security` + (em **T-M0-2**) um `SecurityFilterChain` **permissivo**
(`permitAll` em `/api/v1/**` e `/actuator/**`), CSRF desabilitado (API stateless), CORS habilitado e um
bean `PasswordEncoder` (BCrypt). **Login/emissão/validação de JWT ficam para M1.** Sem o `permitAll`, o
starter-security bloquearia o health com a senha padrão gerada (401).

## Front-end — organização por feature (feature-based, AD-SQ-5 / SPEC §3.7)

Repositório `floricultura-web` (Angular standalone, SCSS):

```
src/app/
├─ core/      # ApiService, interceptors, guards, models (ex.: api-response.model.ts)
├─ shared/    # componentes/ui reutilizáveis
├─ features/  # produtos/, estoque/, eventos/, ... (vazio em M0)
├─ layout/    # shell / toolbar
└─ app.config.ts, app.routes.ts, app.component.*
src/environments/
├─ environment.ts       # dev  (apiBaseUrl: http://localhost:8080/api/v1)
└─ environment.prod.ts  # prod
```

O `ApiService` base lê a URL de `environment.apiBaseUrl` e consome os endpoints sob `/api/v1`,
desserializando o envelope tipado (`ApiResponse<T>`).

## Contrato REST base

### Versionamento e health (SPEC §3.3)

- Todas as rotas de aplicação sob **`/api/v1`** (servidor na raiz `/`, sem context-path extra).
- **`GET /api/v1/health-check`** → `200` + envelope de sucesso `data: { "status": "UP" }` (enveloped).
- **`GET /actuator/health`** → `200` `{"status":"UP"}` no **formato nativo do Actuator** (infra, isento
  de envelope — intencional, não "corrigir").

### Envelope de resposta (SPEC §3.1 / AD-SQ-4)

Toda resposta de `/api/v1/**` usa o envelope único `{ success, data, error, timestamp, path }`:

**Sucesso** (`success: true`, `error: null`):

```json
{
  "success": true,
  "data": { "status": "UP" },
  "error": null,
  "timestamp": "2026-08-31T14:00:00Z",
  "path": "/api/v1/health-check"
}
```

**Erro** (`success: false`, `data: null`):

```json
{
  "success": false,
  "data": null,
  "error": { "code": "NOT_FOUND", "message": "Recurso nao encontrado.", "details": [] },
  "timestamp": "2026-08-31T14:00:00Z",
  "path": "/api/v1/inexistente"
}
```

Erros de validação preenchem `error.details` com `{ field, message }` por campo.

- `timestamp` em UTC ISO-8601 (`Instant`); `path` = URI da requisição.
- Em sucesso, `error` é sempre `null`; em erro, `data` é sempre `null`.
- Tipos Java (T-M0-2): `record ApiResponse<T>(...)`, `record ApiError(...)`, `record FieldErrorItem(...)`.

### Códigos de erro × HTTP (SPEC §3.2)

| `error.code`       | HTTP | Quando |
|--------------------|------|--------|
| `VALIDATION_ERROR` | 400  | Corpo/parâmetro inválido (`@Valid`) |
| `UNAUTHORIZED`     | 401  | Sem credencial válida (a partir de M1) |
| `FORBIDDEN`        | 403  | Sem permissão de role (a partir de M1) |
| `NOT_FOUND`        | 404  | Recurso/rota inexistente sob `/api/v1` |
| `CONFLICT`         | 409  | Violação de unicidade/estado |
| `INTERNAL_ERROR`   | 500  | Exceção não tratada (mensagem genérica; stacktrace só em log) |

Nenhuma mensagem de erro pode vazar stacktrace, SQL ou segredo no corpo (só em log de servidor).

### CORS (SPEC §3.4)

Origem dev `http://localhost:4200` (propriedade `app.cors.allowed-origins`, lida de env var em prod);
métodos `GET, POST, PUT, PATCH, DELETE, OPTIONS`; headers `Authorization, Content-Type`;
`allowCredentials: true`; `maxAge: 3600`. Implementado (T-M0-2) via `CorsConfigurationSource`
referenciado pelo `SecurityFilterChain`.

## Testes (AD-SQ-11)

M0 é scaffolding → escopo **smoke**: back com `contextLoads` (`@SpringBootTest`) e, a partir de T-M0-2,
`HealthCheckControllerTest` (MockMvc: health `200` + envelope; rota inexistente `404` + envelope de
erro). Em T-M0-1 o `contextLoads` roda **sem banco** (auto-config de datasource/JPA/Flyway excluída no
teste), pois ainda não há entidades nem migração; a subida real contra PostgreSQL é validada em T-M0-3.
