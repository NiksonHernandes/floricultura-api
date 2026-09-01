-- V2 — M1: coluna de senha provisoria + seed idempotente do 1o ADMIN.
ALTER TABLE usuario ADD COLUMN senha_provisoria BOOLEAN NOT NULL DEFAULT FALSE;

-- Seed do 1o ADMIN SEM segredo no repositorio: e-mail e HASH BCrypt vem de placeholders Flyway
-- resolvidos por variaveis de ambiente (spring.flyway.placeholders.* <- APP_SEED_ADMIN_*).
-- Guarda: nao insere nada se as variaveis estiverem vazias (no-op seguro).
-- senha_provisoria=TRUE forca a troca no 1o login. Idempotente por e-mail (ON CONFLICT).
INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria)
SELECT 'Administrador', '${seed_admin_email}', '${seed_admin_senha_hash}', 'ADMIN', TRUE, TRUE
WHERE '${seed_admin_email}' <> '' AND '${seed_admin_senha_hash}' <> ''
ON CONFLICT (email) DO NOTHING;
