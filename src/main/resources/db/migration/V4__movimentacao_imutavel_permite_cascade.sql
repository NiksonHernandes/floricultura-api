-- V4 — M2: refina trg_movimentacao_imutavel para permitir SOMENTE a anulacao do FK pelo cascade
-- (produto_id: valor -> NULL, com TODAS as demais colunas identicas). Todo outro UPDATE e todo DELETE
-- continuam rejeitados: o ledger permanece imutavel em conteudo (AD-SQ-8), so o vinculo e anulavel.
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
       AND NEW.criado_em = OLD.criado_em THEN
        RETURN NEW; -- cascade do hard delete de produto (FC-08): permitido
    END IF;
    RAISE EXCEPTION 'movimentacao_estoque e imutavel (insert-only): operacao % negada', TG_OP;
END;
$$ LANGUAGE plpgsql;
-- Os dois triggers (movimentacao_no_update / movimentacao_no_delete) do V1 continuam apontando para
-- esta funcao; nao e preciso recria-los.
