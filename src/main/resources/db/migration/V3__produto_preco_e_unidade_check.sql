-- V3 — M2: preco opcional + enum fixo de unidade_medida + indice de ordenacao por nome.
ALTER TABLE produto ADD COLUMN preco NUMERIC(14,2);
ALTER TABLE produto ADD CONSTRAINT ck_produto_preco CHECK (preco IS NULL OR preco >= 0);

-- unidade_medida: enum fixo (codigos ASCII; 'm3' e exibido como 'm³' no front — AD-SQ-31).
ALTER TABLE produto ADD CONSTRAINT ck_produto_unidade
    CHECK (unidade_medida IN ('un', 'kg', 'saco', 'm3', 'l', 'g'));

-- Ordenacao/paginação por nome (ILIKE de filtro nao usa este indice; ele serve ao ORDER BY nome).
CREATE INDEX ix_produto_nome ON produto (nome);
