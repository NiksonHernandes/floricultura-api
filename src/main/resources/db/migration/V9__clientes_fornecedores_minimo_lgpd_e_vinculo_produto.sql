-- V9__clientes_fornecedores_minimo_lgpd_e_vinculo_produto.sql
-- M5: minimização (remove CPF/CNPJ, endereço, tipo_pessoa; adiciona observacoes; telefone string livre)
-- + N:N informativo <cadastro>↔produto (padrão evento_produto/AD-SQ-44). DROP seguro: stubs V1 sem dados.

-- ---- Cliente ----
ALTER TABLE cliente DROP COLUMN tipo_pessoa;                    -- PF/PJ fora do escopo
ALTER TABLE cliente DROP COLUMN documento;                     -- CPF/CNPJ fora do escopo (minimização LGPD)
ALTER TABLE cliente DROP COLUMN endereco;                      -- endereço fora do escopo (minimização)
ALTER TABLE cliente ALTER COLUMN telefone TYPE VARCHAR(40);    -- string livre (sem máscara rígida)
ALTER TABLE cliente ADD  COLUMN observacoes VARCHAR(500);      -- opcional, ≤500 (aviso anti-dado-sensível no front)

-- ---- Fornecedor (espelho) ----
ALTER TABLE fornecedor DROP COLUMN tipo_pessoa;
ALTER TABLE fornecedor DROP COLUMN documento;
ALTER TABLE fornecedor DROP COLUMN endereco;
ALTER TABLE fornecedor ALTER COLUMN telefone TYPE VARCHAR(40);
ALTER TABLE fornecedor ADD  COLUMN observacoes VARCHAR(500);

CREATE INDEX ix_cliente_nome    ON cliente (nome);             -- ordena lista + acelera ILIKE
CREATE INDEX ix_fornecedor_nome ON fornecedor (nome);

-- ---- N:N cliente↔produto (informativo; links puros, cascade dos dois lados — hard delete FC-08) ----
CREATE TABLE cliente_produto (
    cliente_id BIGINT NOT NULL,
    produto_id BIGINT NOT NULL,
    CONSTRAINT pk_cliente_produto PRIMARY KEY (cliente_id, produto_id),
    CONSTRAINT fk_cp_cliente FOREIGN KEY (cliente_id) REFERENCES cliente(id) ON DELETE CASCADE,
    CONSTRAINT fk_cp_produto FOREIGN KEY (produto_id) REFERENCES produto(id) ON DELETE CASCADE
);
CREATE INDEX ix_cp_produto ON cliente_produto (produto_id);   -- lookup por produto (detalhe/relatório futuro)

-- ---- N:N fornecedor↔produto (espelho) ----
CREATE TABLE fornecedor_produto (
    fornecedor_id BIGINT NOT NULL,
    produto_id    BIGINT NOT NULL,
    CONSTRAINT pk_fornecedor_produto PRIMARY KEY (fornecedor_id, produto_id),
    CONSTRAINT fk_fp_fornecedor FOREIGN KEY (fornecedor_id) REFERENCES fornecedor(id) ON DELETE CASCADE,
    CONSTRAINT fk_fp_produto    FOREIGN KEY (produto_id)    REFERENCES produto(id)    ON DELETE CASCADE
);
CREATE INDEX ix_fp_produto ON fornecedor_produto (produto_id);
