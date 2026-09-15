package com.floricultura.api.repository;

import com.floricultura.api.domain.Produto;
import com.floricultura.api.web.dto.ProdutoFiltro;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.jpa.domain.Specification;

/**
 * {@link Specification} da lista de produtos (SPEC-M6 §3.6, CA-17..CA-23): filtros <b>escalares</b>
 * (nome, atalho de estoque, faixa de preco, {@code semPreco}, caracteristica e toxicidade), os
 * multivalorados por juncao ({@code corIds}/{@code luz}/{@code eventoIds}) e a ordenacao do contrato.
 * As juncoes entram por {@code EXISTS} correlacionado — <b>nunca</b> {@code JOIN}, que duplicaria a
 * linha do produto e corromperia {@code totalElementos} (armadilha #5 do §12, ver {@link #existe}).
 *
 * <p><b>Criteria API, nao query nativa</b> (§3.6): preserva o {@code @Formula sazonal} e <b>nao</b>
 * materializa o {@code bytea} — a {@code @Entity} nao mapeia {@code imagem} (AD-SQ-38 intacto).
 *
 * <p><b>O {@code ORDER BY} mora aqui, nao no {@code Pageable}</b> (mesmo desenho do
 * {@link ContatoSpecs}): o {@code Sort} do Spring Data nao expressa {@code NULLS LAST} de forma
 * confiavel nas duas direcoes (armadilha #7 do §12), e se viesse preenchido sobrescreveria este
 * {@code orderBy} — por isso o servico passa um {@code Pageable} <b>unsorted</b>. Na contagem o
 * {@code orderBy} e ignorado (guarda do {@code resultType}).
 */
public final class ProdutoSpecs {

    /** {@code ordenarPor} do contrato → atributo da {@code @Entity} (§3.6). */
    private static final Map<String, String> ATRIBUTOS =
            Map.of("nome", "nome", "estoque", "estoqueAtual", "preco", "preco");

    private ProdutoSpecs() {
        // Fabrica estatica — nao instanciavel.
    }

    /**
     * Monta o predicado (dimensoes combinadas com <b>E</b>; {@code IN} dentro de cada multivalorada) e
     * fixa a ordenacao do §3.6 ({@code NULLS LAST} nas duas direcoes + desempate {@code id ASC}).
     */
    public static Specification<Produto> de(ProdutoFiltro filtro) {
        return (root, query, cb) -> {
            List<Predicate> predicados = new ArrayList<>();
            if (filtro.nome() != null) {
                predicados.add(FiltroTexto.contem(cb, root.get("nome"), filtro.nome()));
            }
            if (filtro.estoque() != null) {
                predicados.add(estoque(cb, root, filtro.estoque()));
            }
            if (filtro.precoMin() != null) {
                predicados.add(cb.greaterThanOrEqualTo(root.get("preco"), filtro.precoMin()));
            }
            if (filtro.precoMax() != null) {
                predicados.add(cb.lessThanOrEqualTo(root.get("preco"), filtro.precoMax()));
            }
            if (filtro.semPreco()) {
                predicados.add(cb.isNull(root.get("preco")));
            }
            if (!filtro.caracteristica().isEmpty()) {
                predicados.add(root.get("caracteristica").in(filtro.caracteristica()));
            }
            if (!filtro.toxicidade().isEmpty()) {
                predicados.add(root.get("toxicidade").in(filtro.toxicidade()));
            }
            if (!filtro.corIds().isEmpty()) {
                predicados.add(existe(cb, query, root, "corIds", filtro.corIds()));
            }
            if (!filtro.luz().isEmpty()) {
                predicados.add(existe(cb, query, root, "necessidadeLuz", filtro.luz()));
            }
            if (!filtro.eventoIds().isEmpty()) {
                predicados.add(existe(cb, query, root, "eventoIds", filtro.eventoIds()));
            }
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.orderBy(ordenacao(filtro, root, cb));
            }
            return cb.and(predicados.toArray(new Predicate[0]));
        };
    }

    /**
     * Dimensao multivalorada por juncao ({@code corIds}/{@code luz}/{@code eventoIds}) como
     * <b>{@code EXISTS} correlacionado</b> — {@code exists (select 1 from <juncao> j where
     * j.produto_id = p.id and j.<valor> in (...))}, o SQL literal do §3.6.
     *
     * <p><b>Por que nao {@code JOIN} (armadilha #5 do §12):</b> um produto com 2 das cores
     * selecionadas apareceria <b>2 vezes</b> — a pagina viria com a linha repetida e, pior, o
     * {@code count} da paginacao contaria 2, estourando {@code totalElementos}/{@code totalPaginas}.
     * {@code DISTINCT} mascararia a lista mas nao o {@code count}. O {@code EXISTS} e semi-juncao:
     * para/decide na 1a linha que casa, entao a cardinalidade do produto e preservada por construcao —
     * e o mesmo predicado serve a query de dados e a de contagem, sem divergirem.
     *
     * <p>O {@code IN} interno e o <b>OU</b> da dimensao; o {@code and()} entre os predicados e o
     * <b>E</b> entre dimensoes diferentes (P4). A colecao correlacionada e mapeada {@code LAZY} e
     * {@code @Immutable} na {@code @Entity}: o {@code EXISTS} roda no banco, nada e carregado.
     */
    private static Predicate existe(CriteriaBuilder cb, CriteriaQuery<?> query, Root<Produto> root,
            String vinculo, Collection<?> valores) {
        // Nao-nulo em toda leitura (findAll/count/exists do JpaSpecificationExecutor); so o
        // delete(Specification) passa null — caminho que produto nunca usa. Falha explicita > NPE.
        Objects.requireNonNull(query, "Filtro multivalorado exige CriteriaQuery (nao delete).");
        Subquery<Integer> sub = query.subquery(Integer.class);
        Join<Produto, ?> juncao = sub.correlate(root).join(vinculo);
        return cb.exists(sub.select(cb.literal(1)).where(juncao.in(valores)));
    }

    // Atalhos de estoque (§3.6/P10) — NAO sao categorias exclusivas: SEM_ESTOQUE esta contido em
    // BAIXO, que repete a regra do selo estoqueBaixo (FC-13, atual <= minimo) e inclui o zero.
    private static Predicate estoque(CriteriaBuilder cb, Root<Produto> root, String atalho) {
        Path<BigDecimal> atual = root.get("estoqueAtual");
        return switch (atalho) {
            case "SEM_ESTOQUE" -> cb.equal(atual, BigDecimal.ZERO);
            case "BAIXO" -> cb.lessThanOrEqualTo(atual, root.<BigDecimal>get("estoqueMinimo"));
            default -> cb.greaterThan(atual, BigDecimal.ZERO);
        };
    }

    // ORDER BY do §3.6: NULLS LAST nas DUAS direcoes (o produto sem preco nunca lidera a lista — no
    // desc o Postgres poria os nulos em primeiro se ninguem dissesse o contrario) e desempate
    // OBRIGATORIO id ASC, sem o qual a paginacao repete/omite itens em empate (§12 #6/#7).
    private static List<Order> ordenacao(
            ProdutoFiltro filtro, Root<Produto> root, CriteriaBuilder cb) {
        Path<?> chave = root.get(ATRIBUTOS.get(filtro.ordenarPor()));
        Order primeira =
                filtro.ascendente() ? cb.asc(chave, Nulls.LAST) : cb.desc(chave, Nulls.LAST);
        return List.of(primeira, cb.asc(root.get("id")));
    }
}
