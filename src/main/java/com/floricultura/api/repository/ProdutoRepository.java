package com.floricultura.api.repository;

import com.floricultura.api.domain.Produto;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Acesso a persistencia de {@link Produto} (SPEC-M2 §8). O {@code findAll(Pageable)} herdado cobre a
 * listagem paginada/ordenada (AD-SQ-29); {@code findByNomeContainingIgnoreCase} implementa o filtro
 * {@code ILIKE '%nome%'} (case-insensitive, substring) do contrato de listagem (§3.3) consumido na
 * onda 2 (T-M2-2).
 */
@Repository
public interface ProdutoRepository extends JpaRepository<Produto, Long> {

    Page<Produto> findByNomeContainingIgnoreCase(String nome, Pageable pageable);

    /**
     * Carrega o produto com <b>lock pessimista de escrita</b> ({@code SELECT ... FOR UPDATE}) para a
     * movimentacao de estoque (T-M2-4, AD-SQ-30). A linha do produto fica travada ate o commit da
     * transacao: duas SAIDAS concorrentes serializam aqui, cada uma reavaliando o {@code estoque_atual}
     * ja atualizado pela anterior — impede que ambas passem na checagem e furem o estoque para negativo
     * (§4/§12). Deve ser chamado <b>dentro</b> de uma {@code @Transactional} (senao o lock nao se
     * sustenta). Vazio → produto inexistente (404).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Produto p where p.id = :id")
    Optional<Produto> findByIdForUpdate(@Param("id") Long id);

    // ----- Imagem no banco (M3/AD-SQ-38): binario FORA da @Entity, so por query nativa dedicada. -----

    /**
     * Carrega <b>so</b> o binario + content-type da imagem para servir o endpoint dedicado (SPEC-M3
     * §3.5, T-M3-2). Query <b>nativa</b> projetada: a {@code @Entity Produto} nao mapeia {@code imagem}
     * (bytea), entao este e o unico caminho que materializa o binario — lista/detalhe nunca o trazem.
     * Aliases em camelCase ({@code imagemContentType}) casam os getters de {@link
     * ProdutoImagemProjection}. Vazio → produto inexistente (404).
     */
    @Query(value = "SELECT imagem AS imagem, imagem_content_type AS imagemContentType "
            + "FROM produto WHERE id = :id", nativeQuery = true)
    Optional<ProdutoImagemProjection> findImagemById(@Param("id") Long id);

    /**
     * Leitura <b>leve</b> (SEM bytea) do original: content-type + {@code atualizado_em} em epoch-millis,
     * para o {@code GET .../imagem} do M5.2 decidir 404 e calcular o {@code ETag} <b>sem materializar o
     * binario</b> (SPEC-M5.2 §3.6, T-M5.2-4). Vazio → produto inexistente; {@code imagemContentType}
     * nulo → produto sem imagem. Aliases camelCase casam {@link ProdutoImagemMetaProjection}.
     */
    @Query(value = "SELECT imagem_content_type AS imagemContentType, "
            + "CAST(EXTRACT(EPOCH FROM atualizado_em) * 1000 AS BIGINT) AS atualizadoEmEpoch "
            + "FROM produto WHERE id = :id", nativeQuery = true)
    Optional<ProdutoImagemMetaProjection> findImagemMetaById(@Param("id") Long id);

    /**
     * Grava/substitui a imagem no banco via {@code UPDATE} <b>nativo</b> (o bytea nao passa pela
     * @Entity) e estampa {@code atualizado_em = now()} — bumpa o {@code v} da URL versionada do front
     * (SPEC-M3 §3.3, T-M3-3). Retorna a contagem de linhas afetadas: {@code 0} → produto inexistente
     * (404).
     */
    @Modifying
    @Query(value = "UPDATE produto SET imagem = :bytes, imagem_content_type = :contentType, "
            + "imagem_filename = :filename, atualizado_em = now() WHERE id = :id", nativeQuery = true)
    int atualizarImagem(
            @Param("id") Long id,
            @Param("bytes") byte[] bytes,
            @Param("contentType") String contentType,
            @Param("filename") String filename);

    /**
     * Remove a imagem do banco (zera bytea + metadados) via {@code UPDATE} <b>nativo</b> e estampa
     * {@code atualizado_em = now()} (SPEC-M3 §3.3, T-M3-2). Idempotente no servico: produto existente
     * sem imagem ainda afeta 1 linha (→ 204). Retorna a contagem de linhas: {@code 0} → produto
     * inexistente (404).
     */
    @Modifying
    @Query(value = "UPDATE produto SET imagem = NULL, imagem_content_type = NULL, "
            + "imagem_filename = NULL, atualizado_em = now() WHERE id = :id", nativeQuery = true)
    int removerImagem(@Param("id") Long id);

    // ----- Variantes da imagem (M5.2/AD-SQ-38): derivados thumb/medio em tabela dedicada, binario
    // ----- FORA da @Entity, so por query nativa dedicada (mesmo invariante da coluna produto.imagem). -----

    /**
     * Carrega <b>so</b> o binario + content-type de uma variante (thumb/medio) para servir o endpoint
     * {@code GET /produtos/{id}/imagem?tamanho=} (SPEC-M5.2 §3.2/§3.4, T-M5.2-4). Query <b>nativa</b>
     * projetada sobre {@code produto_imagem_variante} (V11, sem @Entity): materializa o {@code bytea} da
     * variante <b>so ao servir</b> — lista/detalhe nunca o trazem (AD-SQ-38). Aliases em camelCase
     * ({@code imagemContentType}) casam os getters de {@link ProdutoImagemVarianteProjection}. Vazio →
     * variante ausente (produto legado M3 / upload que so gravou o original) → o servico cai no fallback
     * gracioso ao original ({@code produto.imagem}), nunca 404 por variante ausente (§3.2/C-R5).
     */
    @Query(value = "SELECT imagem AS imagem, imagem_content_type AS imagemContentType "
            + "FROM produto_imagem_variante WHERE produto_id = :produtoId AND tamanho = :tamanho",
            nativeQuery = true)
    Optional<ProdutoImagemVarianteProjection> findVarianteById(
            @Param("produtoId") Long produtoId, @Param("tamanho") String tamanho);

    /**
     * Grava/substitui uma variante (thumb/medio) via {@code INSERT ... ON CONFLICT (produto_id, tamanho)
     * DO UPDATE} <b>nativo</b> — o bytea nao passa pela @Entity (SPEC-M5.2 §3.4, T-M5.2-3). O parametro
     * {@code bytes} sao os bytes crus da variante (coluna {@code imagem BYTEA}); {@code tamanhoBytes} e o
     * tamanho em bytes gravado na coluna {@code bytes BIGINT}. {@code criado_em} e reestampado no replace
     * (reflete o upload que gerou a variante). O CHECK do schema barra {@code tamanho} fora de
     * thumb/medio e {@code contentType} fora de {@code image/webp}|{@code image/jpeg}.
     */
    @Modifying
    @Query(value = "INSERT INTO produto_imagem_variante "
            + "(produto_id, tamanho, imagem, imagem_content_type, largura, altura, bytes, criado_em) "
            + "VALUES (:produtoId, :tamanho, :bytes, :contentType, :largura, :altura, :tamanhoBytes, now()) "
            + "ON CONFLICT (produto_id, tamanho) DO UPDATE SET "
            + "imagem = EXCLUDED.imagem, imagem_content_type = EXCLUDED.imagem_content_type, "
            + "largura = EXCLUDED.largura, altura = EXCLUDED.altura, bytes = EXCLUDED.bytes, "
            + "criado_em = now()", nativeQuery = true)
    void upsertVariante(
            @Param("produtoId") Long produtoId,
            @Param("tamanho") String tamanho,
            @Param("bytes") byte[] bytes,
            @Param("contentType") String contentType,
            @Param("largura") int largura,
            @Param("altura") int altura,
            @Param("tamanhoBytes") long tamanhoBytes);

    /**
     * Apaga <b>todas</b> as variantes do produto via {@code DELETE} <b>nativo</b> (SPEC-M5.2 §3.4). Usado
     * no POST antes de reinserir (substituicao atomica, C-R9) e no DELETE de imagem (limpa variantes,
     * C-R12). Idempotente: {@code 0} linhas quando nao ha variante (produto legado). O hard delete do
     * produto (FC-08) ja limpa por FK {@code ON DELETE CASCADE} — este metodo cobre o replace/remocao.
     */
    @Modifying
    @Query(value = "DELETE FROM produto_imagem_variante WHERE produto_id = :produtoId",
            nativeQuery = true)
    void deleteVariantesById(@Param("produtoId") Long produtoId);

    // ----- Vinculo N:N produto<->evento (M4/AD-SQ-44): replace-set por queries nativas dedicadas. -----

    /** Ids dos eventos vinculados ao produto (detalhe {@code GET /{id}} — §3.4/CA-9), ordenados. */
    @Query(value = "SELECT evento_id FROM evento_produto WHERE produto_id = :produtoId "
            + "ORDER BY evento_id", nativeQuery = true)
    List<Long> findEventoIdsByProdutoId(@Param("produtoId") Long produtoId);

    /**
     * Produtos vinculados a um evento, paginados (vitrine — SPEC-M4.1 §3.1/§4.1, T-M4.1-1). Query
     * <b>nativa</b> com {@code JOIN evento_produto} + {@code countQuery} equivalente; o {@code ORDER BY}
     * ({@code nome ASC}) vem do {@link Pageable} montado no servico.
     *
     * <p><b>Desvio deliberado do {@code SELECT p.*} do §3.1</b> (justificado pela ancora #3/AD-SQ-38):
     * as colunas sao <b>enumeradas</b> para <b>excluir o {@code imagem} BYTEA</b> — {@code p.*} arrastaria
     * o binario ao {@code ResultSet}, violando o invariante "leitura nunca materializa o bytea". A
     * @Entity exige que a coluna do {@code @Formula sazonal} exista no {@code ResultSet} sob query nativa;
     * como todo item do JOIN e, por definicao, vinculado, projeta-se a constante {@code TRUE AS sazonal}
     * (nao se replica o subselect do {@code @Formula} — §12/PA#2) e o servico ainda fixa {@code true} no
     * mapeamento. Aliases casam os {@code @Column} da @Entity (hidratacao por nome).
     *
     * <p><b>⚠️ Esta projecao acompanha a @Entity (SPEC-M6 §3.5/§12 #3).</b> Toda coluna mapeada em
     * {@link Produto} precisa existir no {@code ResultSet} — por isso o M6 acrescentou
     * {@code p.caracteristica}, {@code p.altura_cm} e {@code p.toxicidade}. Esquecer quebra a vitrine
     * com erro obscuro de coluna ausente; e trocar tudo por {@code p.*} "resolveria" arrastando o
     * {@code bytea} de volta — proibido (AD-SQ-38/AD-SQ-50). Coluna nova na @Entity ⇒ coluna nova aqui.
     */
    @Query(value = "SELECT p.id, p.nome, p.descricao, p.unidade_medida, p.estoque_minimo, "
            + "p.estoque_atual, p.preco, p.imagem_url, p.imagem_content_type, p.imagem_filename, "
            + "p.ativo, p.criado_em, p.atualizado_em, "
            + "p.caracteristica, p.altura_cm, p.toxicidade, TRUE AS sazonal "
            + "FROM produto p JOIN evento_produto ep ON ep.produto_id = p.id "
            + "WHERE ep.evento_id = :eventoId",
            countQuery = "SELECT count(*) FROM produto p JOIN evento_produto ep "
                    + "ON ep.produto_id = p.id WHERE ep.evento_id = :eventoId",
            nativeQuery = true)
    Page<Produto> buscarPorEvento(@Param("eventoId") Long eventoId, Pageable pageable);

    /** Apaga todos os vinculos do produto (1o passo do replace-set — §4.2). */
    @Modifying
    @Query(value = "DELETE FROM evento_produto WHERE produto_id = :produtoId", nativeQuery = true)
    void removerVinculosDoProduto(@Param("produtoId") Long produtoId);

    /**
     * Insere um vinculo produto<->evento (2o passo do replace-set — §4.2). {@code ON CONFLICT DO
     * NOTHING} torna a insercao idempotente para ids repetidos ja deduplicados no servico.
     */
    @Modifying
    @Query(value = "INSERT INTO evento_produto (produto_id, evento_id) VALUES (:produtoId, :eventoId) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    void inserirVinculo(@Param("produtoId") Long produtoId, @Param("eventoId") Long eventoId);

    // ----- Atributos MULTIVALORADOS (M6/§3.5): `produto_cor` e `produto_necessidade_luz` SEM @Entity,
    // ----- so por query nativa dedicada (padrao de evento_produto/AD-SQ-44 e da variante/AD-SQ-76).
    // ----- Mapea-las poria colecao na @Entity e quebraria a hidratacao de `buscarPorEvento` (§12 #3).

    /**
     * Cores do produto para o <b>detalhe</b> (§3.4), com {@code hex}, ordenadas por {@code c.nome ASC}.
     * Aliases casam {@link CorReferenciaProjection}. Na lista nunca e chamada (CA-13/AD-SQ-38).
     */
    @Query(value = "SELECT c.id AS id, c.nome AS nome, c.hex AS hex FROM produto_cor pc "
            + "JOIN cor c ON c.id = pc.cor_id WHERE pc.produto_id = :produtoId ORDER BY c.nome",
            nativeQuery = true)
    List<CorReferenciaProjection> findCoresByProdutoId(@Param("produtoId") Long produtoId);

    /**
     * Ids de {@code corIds} que <b>existem</b> no catalogo — <b>uma unica query</b> para validar o
     * payload antes de qualquer escrita (§3.5/R10/CA-11); nunca {@code findById} em laco. Cumpre o papel
     * do {@code contarCoresInexistentes} do §3.5 devolvendo os <b>ids</b> e nao um {@code long}: o §3.3
     * exige o id na mensagem, que a contagem nao revela. Nao invocar com lista vazia (o {@code IN ()}
     * e erro de sintaxe no Postgres).
     */
    @Query(value = "SELECT id FROM cor WHERE id IN (:corIds)", nativeQuery = true)
    List<Long> findCorIdsExistentes(@Param("corIds") Collection<Long> corIds);

    /** Apaga todos os vinculos de cor do produto (1o passo do replace-set — §3.5/R9). */
    @Modifying
    @Query(value = "DELETE FROM produto_cor WHERE produto_id = :produtoId", nativeQuery = true)
    void removerCoresDoProduto(@Param("produtoId") Long produtoId);

    /** Insere um vinculo produto&lt;-&gt;cor (2o passo do replace-set); {@code ON CONFLICT} = idempotente. */
    @Modifying
    @Query(value = "INSERT INTO produto_cor (produto_id, cor_id) VALUES (:produtoId, :corId) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    void inserirCor(@Param("produtoId") Long produtoId, @Param("corId") Long corId);

    /** Necessidades de luz do produto para o <b>detalhe</b> (§3.4), ordenadas por {@code luz}. */
    @Query(value = "SELECT luz FROM produto_necessidade_luz WHERE produto_id = :produtoId "
            + "ORDER BY luz", nativeQuery = true)
    List<String> findLuzesByProdutoId(@Param("produtoId") Long produtoId);

    /** Apaga todas as necessidades de luz do produto (1o passo do replace-set — §3.5/R9). */
    @Modifying
    @Query(value = "DELETE FROM produto_necessidade_luz WHERE produto_id = :produtoId",
            nativeQuery = true)
    void removerLuzesDoProduto(@Param("produtoId") Long produtoId);

    /** Insere uma necessidade de luz (2o passo do replace-set); {@code ON CONFLICT} = idempotente. */
    @Modifying
    @Query(value = "INSERT INTO produto_necessidade_luz (produto_id, luz) VALUES (:produtoId, :luz) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    void inserirLuz(@Param("produtoId") Long produtoId, @Param("luz") String luz);

    // ----- Relacionamentos derivados do produto (M5-revisao/AD-SQ-66, §R3.5): leitura read-only por
    // ----- NOME, colunas enumeradas (sem bytea/AD-SQ-38), JOIN na tabela VIVA (nome atual; cadastro
    // ----- hard-deletado sai por id nulo no ledger -> INNER JOIN nao casa -> id sempre nao-nulo). -----

    /**
     * Eventos vinculados ao produto (via evento_produto/AD-SQ-44) — os <b>3 mais recentemente
     * cadastrados</b> (M5.1/HISTORIA #6, T-M5.1-7, AD-SQ-73). <b>Decisao consciente (proxy):</b> a
     * juncao {@code evento_produto} e PK {@code (produto_id, evento_id)} <b>sem coluna de data</b> — nao
     * ha timestamp do vinculo. Usa-se {@code e.id DESC} como PROXY de recencia de cadastro ({@code id} e
     * monotono/serial ≈ ordem de criacao), com {@code e.nome ASC} como desempate estavel, e {@code LIMIT
     * 3}. Nao e "os 3 mais iminentes" (por data do evento) — se o dono quiser proximidade, e decisao
     * maior (duplicaria AD-SQ-47) e deve ser escalada, nao improvisada aqui.
     */
    @Query(value = "SELECT e.id AS id, e.nome AS nome FROM evento_produto ep "
            + "JOIN evento e ON e.id = ep.evento_id WHERE ep.produto_id = :id "
            + "ORDER BY e.id DESC, e.nome ASC LIMIT 3",
            nativeQuery = true)
    List<ReferenciaSimplesProjection> findEventosRelacionados(@Param("id") Long id);

    /**
     * Fornecedores das ENTRADAS deste produto — os <b>3 com a movimentacao mais recente</b>
     * (M5.1/HISTORIA #6, T-M5.1-7, AD-SQ-73). Agrupa por {@code (f.id, f.nome)} (equivale ao DISTINCT
     * anterior) e ordena por {@code MAX(m.criado_em) DESC} (recencia real da ENTRADA no ledger), com
     * {@code f.nome ASC} como desempate estavel, e {@code LIMIT 3}. {@code criado_em} e {@code NOT NULL}
     * (V1 baseline, indexado em {@code ix_mov_criado_em}).
     */
    @Query(value = "SELECT f.id AS id, f.nome AS nome FROM movimentacao_estoque m "
            + "JOIN fornecedor f ON f.id = m.fornecedor_id "
            + "WHERE m.produto_id = :id AND m.tipo = 'ENTRADA' "
            + "GROUP BY f.id, f.nome ORDER BY MAX(m.criado_em) DESC, f.nome ASC LIMIT 3",
            nativeQuery = true)
    List<ReferenciaSimplesProjection> findFornecedoresRelacionados(@Param("id") Long id);

    /**
     * Clientes das SAIDAS deste produto — os <b>3 com a movimentacao mais recente</b> (M5.1/HISTORIA #6,
     * T-M5.1-7, AD-SQ-73). Espelha {@code findFornecedoresRelacionados}: agrupa por {@code (c.id,
     * c.nome)} e ordena por {@code MAX(m.criado_em) DESC} (recencia real da SAIDA no ledger), com
     * {@code c.nome ASC} como desempate, e {@code LIMIT 3}.
     */
    @Query(value = "SELECT c.id AS id, c.nome AS nome FROM movimentacao_estoque m "
            + "JOIN cliente c ON c.id = m.cliente_id "
            + "WHERE m.produto_id = :id AND m.tipo = 'SAIDA' "
            + "GROUP BY c.id, c.nome ORDER BY MAX(m.criado_em) DESC, c.nome ASC LIMIT 3",
            nativeQuery = true)
    List<ReferenciaSimplesProjection> findClientesRelacionados(@Param("id") Long id);
}
