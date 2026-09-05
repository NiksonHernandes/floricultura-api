-- V10__movimentacao_contraparte_e_trigger.sql
-- M5 (revisao): contraparte da movimentacao (AD-SQ-64) - ENTRADA pode registrar fornecedor,
-- SAIDA pode registrar cliente (AJUSTE nunca). FK anulavel (ON DELETE SET NULL) + snapshot do nome,
-- para o hard delete LGPD (FC-08) do cadastro NAO apagar o historico do ledger imutavel (padrao
-- AD-SQ-45/usuario_nome). A trigger de imutabilidade e endurecida para tolerar o cascade de anulacao
-- de cliente_id/fornecedor_id (alem do produto_id ja tolerado desde V4), mantendo tudo o mais imutavel.

ALTER TABLE movimentacao_estoque ADD COLUMN fornecedor_id   BIGINT;
ALTER TABLE movimentacao_estoque ADD COLUMN fornecedor_nome VARCHAR(150);
ALTER TABLE movimentacao_estoque ADD COLUMN cliente_id      BIGINT;
ALTER TABLE movimentacao_estoque ADD COLUMN cliente_nome    VARCHAR(150);

ALTER TABLE movimentacao_estoque
    ADD CONSTRAINT fk_mov_fornecedor FOREIGN KEY (fornecedor_id) REFERENCES fornecedor(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_mov_cliente    FOREIGN KEY (cliente_id)    REFERENCES cliente(id)    ON DELETE SET NULL,
    ADD CONSTRAINT ck_mov_fornecedor_tipo CHECK (fornecedor_id IS NULL OR tipo = 'ENTRADA'),
    ADD CONSTRAINT ck_mov_cliente_tipo    CHECK (cliente_id    IS NULL OR tipo = 'SAIDA');

CREATE INDEX ix_mov_fornecedor_tipo ON movimentacao_estoque (fornecedor_id, tipo);
CREATE INDEX ix_mov_cliente_tipo    ON movimentacao_estoque (cliente_id, tipo);

-- Endurece trg_movimentacao_imutavel (V1/V4/V7): o cascade de anulacao agora vale para produto_id,
-- cliente_id e fornecedor_id (FK ON DELETE SET NULL). usuario_id continua INALTERAVEL (AD-SQ-48:
-- remocao de usuario e desativacao, nao hard delete). Todo o resto continua rejeitado; os snapshots
-- de nome (produto/usuario/fornecedor/cliente) sao imutaveis (IS NOT DISTINCT FROM).
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
