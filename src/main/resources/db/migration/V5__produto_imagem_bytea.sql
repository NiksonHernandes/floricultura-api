-- V5 — M3: armazenamento da imagem do produto NO banco (bytea) + metadados (AD-SQ-37/AD-SQ-38).
-- Aditivo/nao-destrutivo: colunas anulaveis; convive com imagem_url externa do M2 (FC-09 revisado).
-- CRITICO: a coluna `imagem` (bytea) NAO e mapeada na @Entity Produto — lista/detalhe nunca a selecionam.
-- So o endpoint GET /produtos/{id}/imagem carrega o binario, via query nativa projetada (AD-SQ-38).
ALTER TABLE produto ADD COLUMN imagem              BYTEA;
ALTER TABLE produto ADD COLUMN imagem_content_type VARCHAR(100);
ALTER TABLE produto ADD COLUMN imagem_filename     VARCHAR(255);

-- Coerencia: ou ha imagem completa (bytea + content_type) ou nao ha nenhuma.
-- (imagem_filename e informativo e pode ser NULL mesmo com imagem — fora do invariante.)
ALTER TABLE produto ADD CONSTRAINT ck_produto_imagem_coerente
    CHECK ((imagem IS NULL AND imagem_content_type IS NULL)
        OR (imagem IS NOT NULL AND imagem_content_type IS NOT NULL));

-- content_type restrito a whitelist (defesa em profundidade; validacao forte + anti-spoofing no back).
ALTER TABLE produto ADD CONSTRAINT ck_produto_imagem_tipo
    CHECK (imagem_content_type IS NULL
        OR imagem_content_type IN ('image/jpeg', 'image/png', 'image/webp'));
