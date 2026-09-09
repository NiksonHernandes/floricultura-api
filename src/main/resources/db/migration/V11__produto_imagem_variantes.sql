-- V11__produto_imagem_variantes.sql
-- M5.2 (HISTORIA C / PA#3): storage dos DERIVADOS da imagem do produto (thumb ~200px, medio ~800px)
-- em tabela dedicada. O original comprimido (<=1280px) permanece em produto.imagem (V5, canonico) —
-- dirige temImagem e a coexistencia com legado M3 (SDD §3.4). Aditiva: nao toca dado existente.
--
-- CRITICO (AD-SQ-38): esta tabela NAO e mapeada como @Entity; nenhum bytea (original ou variante) e
-- carregado por entidade. So queries NATIVAS dedicadas materializam o binario (ProdutoRepository:
-- findVarianteById/upsertVariante/deleteVariantesById). O ddl-auto=validate ignora tabelas nao
-- mapeadas (mesmo precedente da coluna produto.imagem — V5/§12), entao o boot validate segue verde.
--
-- FK ON DELETE CASCADE: o hard delete do produto (FC-08) limpa as variantes automaticamente.
-- PK composta (produto_id, tamanho): 1 linha por variante; o indice do PK cobre o lookup do GET.
-- CHECKs: tamanho restrito a thumb/medio (o original NAO se duplica aqui); content_type restrito ao
-- formato real gravado pelo pipeline (webp ou jpeg-fallback — SDD §3.5).
CREATE TABLE produto_imagem_variante (
    produto_id           BIGINT       NOT NULL,
    tamanho              VARCHAR(10)  NOT NULL,
    imagem               BYTEA        NOT NULL,
    imagem_content_type  VARCHAR(30)  NOT NULL,
    largura              INT          NOT NULL,
    altura               INT          NOT NULL,
    bytes                BIGINT       NOT NULL,
    criado_em            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_produto_imagem_variante PRIMARY KEY (produto_id, tamanho),
    CONSTRAINT fk_piv_produto FOREIGN KEY (produto_id) REFERENCES produto(id) ON DELETE CASCADE,
    CONSTRAINT ck_piv_tamanho CHECK (tamanho IN ('thumb','medio')),
    CONSTRAINT ck_piv_tipo    CHECK (imagem_content_type IN ('image/webp','image/jpeg'))
);
-- indice do PK ja cobre lookup por (produto_id, tamanho); FK cascade limpa no hard delete do produto.
