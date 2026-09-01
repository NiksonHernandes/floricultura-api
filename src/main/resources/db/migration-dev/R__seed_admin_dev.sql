-- R__seed_admin_dev.sql — SOMENTE db/migration-dev (ligado apenas no profile dev, application-dev.yml).
-- Cria o ADMIN padrao de DESENVOLVIMENTO sem exigir nenhuma variavel de ambiente.
-- Credencial DEV CONHECIDA (e-mail admin@floricultura.local / senha "admin"); NUNCA sobe em prod:
-- prod usa spring.flyway.locations = so classpath:db/migration (este diretorio nao existe la) — AD-SQ-23.
-- senha_provisoria=FALSE: o 1o acesso NAO forca troca (AD-SQ-24). Repeatable idempotente (ON CONFLICT).
INSERT INTO usuario (nome, email, senha_hash, role, ativo, senha_provisoria)
VALUES ('Administrador (dev)', 'admin@floricultura.local',
        '$2a$10$KatbCofXNjrvPPwUoqWpkuR765b0mkoMV4qNJ5BEqgb7SlF8O3JVi', 'ADMIN', TRUE, FALSE)
ON CONFLICT (email) DO NOTHING;
