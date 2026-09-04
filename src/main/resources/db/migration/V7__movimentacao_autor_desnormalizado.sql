-- V7 — M4: autor da movimentacao desnormalizado (AD-SQ-45, espirito produto_nome/AD-SQ-34).
-- SEM backfill: o ledger e imutavel (AD-SQ-8) e um UPDATE de backfill seria rejeitado pela propria
-- trigger; linhas historicas ficam com usuario_nome NULL (front exibe "—").
ALTER TABLE movimentacao_estoque ADD COLUMN usuario_nome VARCHAR(120);

-- Endurece trg_movimentacao_imutavel (V1/V4): o UNICO UPDATE tolerado (cascade produto_id->NULL do
-- hard delete de produto) tambem NAO pode alterar usuario_nome. Todo o resto continua rejeitado.
-- Padrao V4 preservado: so CREATE OR REPLACE da funcao; os triggers no_update/no_delete (V1) nao mudam.
CREATE OR REPLACE FUNCTION trg_movimentacao_imutavel() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.produto_id IS NOT NULL AND NEW.produto_id IS NULL
       AND NEW.id = OLD.id
       AND NEW.produto_nome = OLD.produto_nome
       AND NEW.tipo = OLD.tipo
       AND NEW.quantidade = OLD.quantidade
       AND NEW.quantidade_resultante = OLD.quantidade_resultante
       AND NEW.motivo IS NOT DISTINCT FROM OLD.motivo
       AND NEW.usuario_id IS NOT DISTINCT FROM OLD.usuario_id
       AND NEW.usuario_nome IS NOT DISTINCT FROM OLD.usuario_nome  -- NOVO guarda (V7)
       AND NEW.criado_em = OLD.criado_em THEN
        RETURN NEW; -- cascade do hard delete de produto (FC-08): permitido
    END IF;
    RAISE EXCEPTION 'movimentacao_estoque e imutavel (insert-only): operacao % negada', TG_OP;
END;
$$ LANGUAGE plpgsql;
