-- V9__clientes_fornecedores_minimo_lgpd_e_vinculo_produto.sql
-- M5 (REVISADO 2026-09-04): minimizacao LGPD dos stubs cliente/fornecedor da V1
-- (remove tipo_pessoa/documento/endereco; adiciona observacoes; telefone string livre).
-- O vinculo <cadastro>-produto deixou de ser juncao N:N e passou a ser DERIVADO da movimentacao
-- (AD-SQ-65): as tabelas cliente_produto/fornecedor_produto FORAM REMOVIDAS desta migracao (branch
-- nao mergeada -> sem migracao de reversao). DROP seguro: stubs V1 sem dados (AD-SQ-8/AD-SQ-59).

-- ---- Cliente ----
ALTER TABLE cliente DROP COLUMN tipo_pessoa;
ALTER TABLE cliente DROP COLUMN documento;
ALTER TABLE cliente DROP COLUMN endereco;
ALTER TABLE cliente ALTER COLUMN telefone TYPE VARCHAR(40);
ALTER TABLE cliente ADD  COLUMN observacoes VARCHAR(500);

-- ---- Fornecedor (espelho) ----
ALTER TABLE fornecedor DROP COLUMN tipo_pessoa;
ALTER TABLE fornecedor DROP COLUMN documento;
ALTER TABLE fornecedor DROP COLUMN endereco;
ALTER TABLE fornecedor ALTER COLUMN telefone TYPE VARCHAR(40);
ALTER TABLE fornecedor ADD  COLUMN observacoes VARCHAR(500);

CREATE INDEX ix_cliente_nome    ON cliente (nome);
CREATE INDEX ix_fornecedor_nome ON fornecedor (nome);
