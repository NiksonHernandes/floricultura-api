-- V13__movimentacao_valores_e_estorno.sql
-- M7 (SPEC-M7 §3.1): dinheiro no ledger insert-only. Migracao ADITIVA pura — as 6 colunas nascem
-- NULL, nenhuma linha existente e tocada (zero DML, zero backfill); ADD COLUMN sem DEFAULT e
-- metadata-only no PostgreSQL >= 11, sem janela de lock relevante.
-- O valor gravado e HISTORICO: nao acompanha produto.preco (V3) e nao e editavel — o erro de
-- digitacao se corrige com OUTRA linha (estorno, D-A/AD-SQ-8), nunca com UPDATE.
-- REVERSAO declarada (best-practice migracao-de-banco): enquanto nenhum valor tiver sido gravado, a
-- inversa e trivial (DROP das 6 colunas + CREATE OR REPLACE da funcao na forma da V10). Depois que
-- houver valor gravado, o DROP COLUMN perde dado e e IRREVERSIVEL — exige gate humano explicito.

ALTER TABLE movimentacao_estoque ADD COLUMN valor_unitario          NUMERIC(14,2);
ALTER TABLE movimentacao_estoque ADD COLUMN desconto_tipo           VARCHAR(12);
ALTER TABLE movimentacao_estoque ADD COLUMN desconto_valor          NUMERIC(14,2);
ALTER TABLE movimentacao_estoque ADD COLUMN total_bruto             NUMERIC(14,2);
ALTER TABLE movimentacao_estoque ADD COLUMN total_final             NUMERIC(14,2);
ALTER TABLE movimentacao_estoque ADD COLUMN estorna_movimentacao_id BIGINT;

-- Constraints com nome LITERAL (a rubrica confere por pg_constraint; nome gerado pelo PG nao e
-- referenciavel em teste nem em migracao futura). NUMERIC(14,2) = o mesmo tipo de produto.preco (V3).
-- Contagem exata (§3.1-b): 10 CHECK + 1 FK = 11 objetos em pg_constraint; os 2 indices ficam em
-- pg_indexes (ux_mov_estorno e PARCIAL, por isso nao aparece em pg_constraint).
ALTER TABLE movimentacao_estoque
    ADD CONSTRAINT ck_mov_valor_unitario
        CHECK (valor_unitario IS NULL OR valor_unitario >= 0),
    ADD CONSTRAINT ck_mov_desconto_tipo
        CHECK (desconto_tipo IS NULL OR desconto_tipo IN ('PERCENTUAL', 'VALOR')),
    ADD CONSTRAINT ck_mov_desconto_par
        CHECK ((desconto_tipo IS NULL) = (desconto_valor IS NULL)),
    ADD CONSTRAINT ck_mov_desconto_valor
        CHECK (desconto_valor IS NULL OR desconto_valor >= 0),
    ADD CONSTRAINT ck_mov_desconto_pct
        CHECK (desconto_tipo <> 'PERCENTUAL' OR desconto_valor <= 100),
    ADD CONSTRAINT ck_mov_desconto_exige_base
        CHECK (desconto_tipo IS NULL OR valor_unitario IS NOT NULL),
    ADD CONSTRAINT ck_mov_totais_par
        CHECK ((valor_unitario IS NULL) = (total_bruto IS NULL)
               AND (total_bruto IS NULL) = (total_final IS NULL)),
    ADD CONSTRAINT ck_mov_total_final
        CHECK (total_final IS NULL OR (total_final >= 0 AND total_final <= total_bruto)),
    -- PA#1: AJUSTE e alvo absoluto de quantidade — nao ha "quantidade comprada" a multiplicar.
    -- Defesa em profundidade da validacao de servico (400 field=valorUnitario), no mesmo espirito de
    -- ck_mov_fornecedor_tipo/ck_mov_cliente_tipo da V10. Desconto sem valor_unitario ja e barrado por
    -- ck_mov_desconto_exige_base, entao "AJUSTE com desconto" tambem cai aqui, por encadeamento.
    ADD CONSTRAINT ck_mov_valor_tipo
        CHECK (valor_unitario IS NULL OR tipo <> 'AJUSTE'),
    ADD CONSTRAINT ck_mov_estorno_nao_self
        CHECK (estorna_movimentacao_id IS NULL OR estorna_movimentacao_id <> id),
    -- SEM cascade: linha de ledger nao se apaga (o DELETE ja e barrado pela trigger).
    ADD CONSTRAINT fk_mov_estorno
        FOREIGN KEY (estorna_movimentacao_id) REFERENCES movimentacao_estoque(id);

-- "Cada lancamento e estornado no maximo UMA vez" — no banco, nao so no servico: a corrida de dois
-- cliques simultaneos morre aqui (o 409 do servico e a porta da frente).
CREATE UNIQUE INDEX ux_mov_estorno ON movimentacao_estoque (estorna_movimentacao_id)
    WHERE estorna_movimentacao_id IS NOT NULL;

-- Serve ao recorte periodo x tipo do relatorio (SPEC-M7 §3.7).
CREATE INDEX ix_mov_tipo_criado_em ON movimentacao_estoque (tipo, criado_em);

-- ENDURECIMENTO da trg_movimentacao_imutavel (V1 -> V4 -> V7 -> V10). A funcao ENUMERA as colunas que
-- precisam continuar identicas para o UPDATE de cascade ser tolerado: coluna nova nao enumerada =
-- BURACO. Antes desta migracao, "UPDATE ... SET produto_id = NULL, valor_unitario = 999" satisfazia
-- todas as igualdades listadas e caia no RETURN NEW — dinheiro ja gravado alterado sem a trigger
-- reclamar (reproduzido em PostgreSQL de descarte com V1..V12 aplicadas: devolveu "UPDATE 1").
-- Abaixo: as MESMAS clausulas da V10, byte a byte (inclusive a ressalva de cascade de
-- produto_id/fornecedor_id/cliente_id e o usuario_id ESTRITO), MAIS as 6 colunas da V13.
-- Divida de desenho aceita (§12 #18/AD-SQ-155): toda migracao que acrescentar coluna ao ledger tem de
-- acrescentar a clausula correspondente AQUI, na mesma migracao.
CREATE OR REPLACE FUNCTION trg_movimentacao_imutavel() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND NEW.id = OLD.id
       AND NEW.produto_nome = OLD.produto_nome
       AND NEW.tipo = OLD.tipo
       AND NEW.quantidade = OLD.quantidade
       AND NEW.quantidade_resultante = OLD.quantidade_resultante
       AND NEW.motivo IS NOT DISTINCT FROM OLD.motivo
       AND NEW.usuario_id IS NOT DISTINCT FROM OLD.usuario_id
       AND NEW.usuario_nome IS NOT DISTINCT FROM OLD.usuario_nome
       AND NEW.fornecedor_nome IS NOT DISTINCT FROM OLD.fornecedor_nome
       AND NEW.cliente_nome IS NOT DISTINCT FROM OLD.cliente_nome
       AND NEW.criado_em = OLD.criado_em
       AND NEW.valor_unitario          IS NOT DISTINCT FROM OLD.valor_unitario
       AND NEW.desconto_tipo           IS NOT DISTINCT FROM OLD.desconto_tipo
       AND NEW.desconto_valor          IS NOT DISTINCT FROM OLD.desconto_valor
       AND NEW.total_bruto             IS NOT DISTINCT FROM OLD.total_bruto
       AND NEW.total_final             IS NOT DISTINCT FROM OLD.total_final
       AND NEW.estorna_movimentacao_id IS NOT DISTINCT FROM OLD.estorna_movimentacao_id
       AND (NEW.produto_id    IS NOT DISTINCT FROM OLD.produto_id    OR (OLD.produto_id    IS NOT NULL AND NEW.produto_id    IS NULL))
       AND (NEW.fornecedor_id IS NOT DISTINCT FROM OLD.fornecedor_id OR (OLD.fornecedor_id IS NOT NULL AND NEW.fornecedor_id IS NULL))
       AND (NEW.cliente_id    IS NOT DISTINCT FROM OLD.cliente_id    OR (OLD.cliente_id    IS NOT NULL AND NEW.cliente_id    IS NULL))
       AND (NEW.produto_id    IS DISTINCT FROM OLD.produto_id
            OR NEW.fornecedor_id IS DISTINCT FROM OLD.fornecedor_id
            OR NEW.cliente_id    IS DISTINCT FROM OLD.cliente_id) THEN
        RETURN NEW; -- cascade do hard delete de produto/cliente/fornecedor (FC-08): permitido
    END IF;
    RAISE EXCEPTION 'movimentacao_estoque e imutavel (insert-only): operacao % negada', TG_OP;
END;
$$ LANGUAGE plpgsql;
