-- V6 — M4: evolui o stub `evento` do M0 (V1, schema-only, sem dados reais — AD-SQ-8/AD-SQ-43)
-- e cria o vinculo N:N produto<->evento (AD-SQ-44). Operacoes destrutivas de coluna sao seguras:
-- a tabela `evento` nunca foi usada por codigo (nenhuma @Entity/insert) — confirmado no gate.
ALTER TABLE evento RENAME COLUMN data_evento TO data_inicio;
ALTER TABLE evento ADD COLUMN data_fim        DATE;                -- NULL => evento de DATA UNICA (AD-SQ-43)
ALTER TABLE evento ADD COLUMN tipo            VARCHAR(20) NOT NULL DEFAULT 'COMEMORATIVA';
ALTER TABLE evento ALTER COLUMN tipo DROP DEFAULT;                 -- default so p/ backfill (0 linhas); enum sem default de negocio
ALTER TABLE evento ADD COLUMN repete_todo_ano BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE evento DROP COLUMN sazonal;                            -- conceito migrou para o N:N (evento_produto, AD-SQ-44)
ALTER TABLE evento DROP COLUMN ativo;                              -- hard delete (FC-08): sem flag de soft-delete

ALTER TABLE evento ADD CONSTRAINT ck_evento_tipo
    CHECK (tipo IN ('COMEMORATIVA', 'FEIRA', 'BENEFICENTE', 'ENCOMENDA_CLIENTE'));  -- enum ASCII+CHECK (AD-SQ-31)
ALTER TABLE evento ADD CONSTRAINT ck_evento_periodo
    CHECK (data_fim IS NULL OR data_fim >= data_inicio);

CREATE INDEX ix_evento_data_inicio ON evento (data_inicio);       -- ordena lista + varredura do /proximos
CREATE INDEX ix_evento_nome        ON evento (nome);

-- N:N produto<->evento — informativo; links puros, cascade dos dois lados (hard delete FC-08).
CREATE TABLE evento_produto (
    evento_id  BIGINT NOT NULL,
    produto_id BIGINT NOT NULL,
    CONSTRAINT pk_evento_produto PRIMARY KEY (evento_id, produto_id),
    CONSTRAINT fk_ep_evento  FOREIGN KEY (evento_id)  REFERENCES evento(id)  ON DELETE CASCADE,
    CONSTRAINT fk_ep_produto FOREIGN KEY (produto_id) REFERENCES produto(id) ON DELETE CASCADE
);
CREATE INDEX ix_ep_produto ON evento_produto (produto_id);        -- lookup por produto (detalhe/@Formula sazonal)
