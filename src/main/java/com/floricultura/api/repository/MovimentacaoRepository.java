package com.floricultura.api.repository;

import com.floricultura.api.domain.MovimentacaoEstoque;
import java.time.Instant;
import java.util.List;
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
     * "Esta linha ja foi estornada?" (M7/T-M7-02, CA-12 — SPEC-M7 §3.4-d). Servida pelo indice unico
     * <b>parcial</b> {@code ux_mov_estorno} da V13.
     *
     * <p><b>O que ela e e o que ela NAO e:</b> e a porta da frente do 409 (resposta honesta no caso
     * sequencial — o operador clicou duas vezes). <b>Nao</b> e exclusao mutua: em {@code READ
     * COMMITTED} duas transacoes concorrentes podem passar por aqui antes de qualquer commit. Quem
     * garante "cada lancamento e estornado no maximo UMA vez" e o proprio indice unico, cuja violacao
     * o controller traduz para o mesmo 409 (SPEC-M7 §3.1-b/§4 #10).
     */
    boolean existsByEstornaMovimentacaoId(Long estornaMovimentacaoId);

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

    // ---- Relatorio (SPEC-M7 §3.7, T-M7-04) ----------------------------------------------------

    /**
     * Recorte do relatorio (§3.7): periodo <b>obrigatorio</b> + os mesmos opcionais do §3.5, com
     * semantica <b>E</b>. {@code de}/{@code ate} chegam ja ancorados em {@code America/Sao_Paulo}
     * pelo {@code FiltroRelatorio} ({@code criado_em >= de AND criado_em < ate}, com o dia final
     * inteiro), entao aqui nao ha aritmetica de fuso — ela acontece uma unica vez, na fronteira.
     *
     * <p>Serve-se do {@code ix_mov_tipo_criado_em} da V13, feito para este recorte periodo x tipo.
     */
    String RECORTE_RELATORIO = "FROM movimentacao_estoque m "
            + "WHERE m.criado_em >= CAST(:de AS timestamptz) "
            + "AND m.criado_em < CAST(:ate AS timestamptz) "
            + "AND (:tipo IS NULL OR m.tipo = :tipo) "
            + "AND (CAST(:produtoId AS bigint) IS NULL "
            + "OR m.produto_id = CAST(:produtoId AS bigint)) "
            + "AND (CAST(:clienteId AS bigint) IS NULL "
            + "OR m.cliente_id = CAST(:clienteId AS bigint)) "
            + "AND (CAST(:fornecedorId AS bigint) IS NULL "
            + "OR m.fornecedor_id = CAST(:fornecedorId AS bigint)) ";

    /**
     * As <b>duas</b> cláusulas que tiram o par estornado da conta (§3.7-d, PA#3) — e sao duas porque
     * as linhas do par saem por motivos diferentes:
     *
     * <ul>
     *   <li>{@code estorna_movimentacao_id IS NULL} derruba a <b>linha de estorno</b> (a que aponta);
     *   <li>o {@code NOT EXISTS} derruba a <b>linha original</b> (a que e apontada).
     * </ul>
     *
     * <p>Faltando qualquer uma, o total erra em <b>direcoes contrarias</b>: sem o {@code IS NULL}, o
     * estorno de uma ENTRADA vira uma SAIDA que inventa receita; sem o {@code NOT EXISTS}, a ENTRADA
     * errada continua somando. Mutacoes §10 #10b e #10c.
     *
     * <p>⚠️ <b>O subselect e IRRESTRITO — nao repete o periodo nem os filtros</b> (§3.7-d, §12 #22).
     * Uma linha que participou de um par <b>nunca soma, em periodo nenhum</b>: e a decisao PA#5 do
     * dono ("o mes fechado corrige retroativamente"). Recortar o subselect pelo periodo faria o
     * lancamento cujo estorno caiu em outro mes <b>voltar</b> ao total — mutacao §10 #10e.
     *
     * <p>Nenhuma das duas cláusulas pode devolver {@code NULL} ({@code IS NULL} e {@code EXISTS} sao
     * sempre booleanos), entao {@code NOT (...)} e o <b>complemento exato</b> — e o que sustenta a
     * identidade {@code somados + excluidos = total do recorte} do contador (§3.7-d, §10 #10f).
     */
    String SEM_PAR_ESTORNADO = "m.estorna_movimentacao_id IS NULL "
            + "AND NOT EXISTS (SELECT 1 FROM movimentacao_estoque e "
            + "WHERE e.estorna_movimentacao_id = m.id)";

    /**
     * Agregacao do relatorio: uma linha por <b>dia de negocio</b> x <b>tipo</b>, ja com contagem e
     * somas feitas no banco (CA-20). O {@code AT TIME ZONE} converte o {@code TIMESTAMPTZ} para o dia
     * em {@code America/Sao_Paulo} <b>uma unica vez, dentro do SQL</b> (armadilha §12 #12).
     *
     * <p><b>Por que por DIA e nao por {@code date_trunc(:granularidade, ...)}:</b> o teto de 366 dias
     * (§3.7) limita o resultado a no maximo 366 x 3 = <b>1 098 linhas ja agregadas</b>, e a dobra em
     * SEMANA/MES vira funcao pura ({@code SerieDePeriodos}), testavel em milissegundos e imune a
     * borda de calendario. De quebra, some o bind de {@code text} em posicao de funcao. A agregacao
     * continua sendo <b>do servidor</b> — o navegador nunca soma (§4 #7).
     *
     * <p>{@code sum(coalesce(total_final, 0))}: lancamento sem dinheiro (as 5 colunas {@code NULL},
     * P6) nao contamina o total com {@code NULL} (§3.7-a).
     *
     * @return linhas {@code [dia (java.time.LocalDate — medido no driver deste projeto), tipo
     *     (String), lancamentos (Long), quantidade (BigDecimal), valor (BigDecimal)]}
     */
    @Query(value = "SELECT CAST((m.criado_em AT TIME ZONE 'America/Sao_Paulo') AS date) AS dia, "
            + "m.tipo AS tipo, count(*) AS lancamentos, "
            + "sum(m.quantidade) AS quantidade, sum(coalesce(m.total_final, 0)) AS valor "
            + RECORTE_RELATORIO
            + "AND (" + SEM_PAR_ESTORNADO + ") "
            + "GROUP BY 1, 2",
            nativeQuery = true)
    List<Object[]> agregarPorDiaETipo(
            @Param("de") Instant de,
            @Param("ate") Instant ate,
            @Param("tipo") String tipo,
            @Param("produtoId") Long produtoId,
            @Param("clienteId") Long clienteId,
            @Param("fornecedorId") Long fornecedorId);

    /**
     * Quantas linhas do <b>mesmo recorte</b> ficaram de fora por participarem de um par estornado —
     * o {@code lancamentosEstornadosExcluidos} do payload (PA#3: omissao <b>declarada</b>).
     *
     * <p>E literalmente o {@code NOT (...)} do predicado da agregacao, sobre o mesmo
     * {@link #RECORTE_RELATORIO}: um contador escrito a parte ("conte os pares do periodo") diria
     * <b>2</b> no par que atravessa a fronteira, quebraria a identidade
     * {@code somados + excluidos = total} e viraria a segunda mentira da nota de rodape (§10 #10f).
     */
    @Query(value = "SELECT count(*) " + RECORTE_RELATORIO + "AND NOT (" + SEM_PAR_ESTORNADO + ")",
            nativeQuery = true)
    long contarExcluidosDoRecorte(
            @Param("de") Instant de,
            @Param("ate") Instant ate,
            @Param("tipo") String tipo,
            @Param("produtoId") Long produtoId,
            @Param("clienteId") Long clienteId,
            @Param("fornecedorId") Long fornecedorId);
}
