package com.floricultura.api.repository;

import com.floricultura.api.domain.MovimentacaoEstoque;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Acesso a persistencia do ledger {@link MovimentacaoEstoque} (SPEC-M2 §8). Insert-only na pratica
 * (AD-SQ-8): {@code save} grava novas linhas; qualquer UPDATE/DELETE e barrado pela trigger de banco
 * {@code trg_movimentacao_imutavel}. {@code findByProdutoId} serve ao historico paginado
 * {@code criadoEm DESC} (§3.3) consumido na T-M2-4.
 */
@Repository
public interface MovimentacaoRepository extends JpaRepository<MovimentacaoEstoque, Long> {

    Page<MovimentacaoEstoque> findByProdutoId(Long produtoId, Pageable pageable);

    /**
     * Predicados da lista global (SPEC-M7 §3.5, T-M7-03, CA-16..CA-19), no padrao normativo
     * {@code :param IS NULL OR <coluna> = :param} — o mesmo texto serve a query de dados e a
     * {@code countQuery}, que por isso <b>nao podem divergir</b>.
     *
     * <p><b>Nenhum JOIN, de proposito.</b> Todos os seis predicados batem em colunas da PROPRIA
     * {@code movimentacao_estoque} (o ledger ja carrega os snapshots de contraparte da V10). Se algum
     * dia um filtro precisar de tabela associativa, o caminho e {@code EXISTS}: um {@code JOIN}
     * multiplica linhas e corrompe o {@code totalElementos} da paginacao (licao do M6).
     *
     * <p><b>O bloco do {@code q} fica entre parenteses</b> — ele e uma cadeia de {@code OR}, e sem
     * isolar, o primeiro {@code AND} novo mudaria a precedencia e o significado do filtro herdado
     * (CA-16 cairia em silencio). Os {@code CAST} explicitos existem porque um parametro que aparece
     * so em {@code ? IS NULL} nao tem tipo inferivel pelo PostgreSQL.
     */
    String FILTROS_GLOBAIS = "WHERE (:q IS NULL "
            + "OR m.produto_nome ILIKE '%' || :q || '%' "
            + "OR m.usuario_nome ILIKE '%' || :q || '%') "
            + "AND (CAST(:de AS timestamptz) IS NULL OR m.criado_em >= CAST(:de AS timestamptz)) "
            + "AND (CAST(:ate AS timestamptz) IS NULL OR m.criado_em < CAST(:ate AS timestamptz)) "
            + "AND (:tipo IS NULL OR m.tipo = :tipo) "
            + "AND (CAST(:produtoId AS bigint) IS NULL "
            + "OR m.produto_id = CAST(:produtoId AS bigint)) "
            + "AND (CAST(:clienteId AS bigint) IS NULL "
            + "OR m.cliente_id = CAST(:clienteId AS bigint)) "
            + "AND (CAST(:fornecedorId AS bigint) IS NULL "
            + "OR m.fornecedor_id = CAST(:fornecedorId AS bigint)) ";

    /**
     * Lista GLOBAL paginada do ledger (M4/T-M4-10, AD-SQ-46, CA-22): filtro {@code q} opcional casando
     * {@code produto_nome} <b>OU</b> {@code usuario_nome} por {@code ILIKE '%q%'}. Query <b>nativa</b> de
     * proposito: o {@code ILIKE} na coluna crua e o que o indice <b>GIN trigram</b> da V8 acelera (um
     * {@code lower(col) LIKE} nao usaria o indice). {@code q} nulo/vazio ⇒ {@code :q IS NULL} ⇒ sem
     * filtro. A ordem {@code criado_em DESC} e <b>fixa no SQL</b> (sustentada por {@code ix_mov_criado_em}
     * da V1); o {@link Pageable} entra apenas com {@code LIMIT/OFFSET} — passe-o <b>sem</b> {@code Sort}
     * (o servico usa {@code Sort.unsorted()}). Paginacao <b>obrigatoria</b>: nunca {@code findAll()} sem
     * {@code Pageable} (CA-23).
     *
     * <p><b>M7 (§3.5):</b> ganha os seis filtros opcionais de {@link #FILTROS_GLOBAIS} — intervalo
     * {@code de}/{@code ate} (ja como {@link Instant}, ancorados em {@code America/Sao_Paulo} pelo
     * {@code FiltroMovimentacao}), {@code tipo} e os tres ids ({@code produtoId}, {@code clienteId},
     * {@code fornecedorId}). Todos {@code null} ⇒ a resposta e <b>identica</b> a de hoje (CA-16); a
     * semantica entre parametros diferentes e <b>E</b>. O recorte periodo x tipo e servido pelo
     * {@code ix_mov_tipo_criado_em} da V13.
     */
    @Query(value = "SELECT * FROM movimentacao_estoque m " + FILTROS_GLOBAIS
            + "ORDER BY m.criado_em DESC",
            countQuery = "SELECT count(*) FROM movimentacao_estoque m " + FILTROS_GLOBAIS,
            nativeQuery = true)
    Page<MovimentacaoEstoque> buscarGlobal(
            @Param("q") String q,
            @Param("de") Instant de,
            @Param("ate") Instant ate,
            @Param("tipo") String tipo,
            @Param("produtoId") Long produtoId,
            @Param("clienteId") Long clienteId,
            @Param("fornecedorId") Long fornecedorId,
            Pageable pageable);

    /**
     * Le o {@code criado_em} (do {@code DEFAULT now()} do banco) de uma linha recem-inserida, na mesma
     * transacao da movimentacao (T-M2-4). A coluna e {@code insertable=false}, entao a entidade em
     * memoria fica com {@code criadoEm=null} apos o INSERT; esta <b>projecao escalar</b> executa um
     * SELECT que <b>nao</b> e servido pelo cache de 1o nivel (ao contrario de {@code findById}),
     * trazendo o valor real gravado pelo banco — sem precisar de {@code EntityManager.refresh} (que
     * exigiria um EMF e quebraria os smokes do M0 que excluem JPA).
     */
    @Query("select m.criadoEm from MovimentacaoEstoque m where m.id = :id")
    Instant findCriadoEmById(@Param("id") Long id);
}
