-- V8 — M4: performance da lista global de movimentacoes (filtro ILIKE por 2 colunas — AD-SQ-46).
-- `criado_em` ja e indexado (ix_mov_criado_em, V1) e sustenta ORDER BY criado_em DESC + paginacao.
-- ILIKE '%termo%' tem curinga a ESQUERDA => btree nao acelera; GIN trigram e o indice correto (PA#1).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_mov_produto_nome_trgm ON movimentacao_estoque USING gin (produto_nome gin_trgm_ops);
CREATE INDEX ix_mov_usuario_nome_trgm ON movimentacao_estoque USING gin (usuario_nome gin_trgm_ops);
