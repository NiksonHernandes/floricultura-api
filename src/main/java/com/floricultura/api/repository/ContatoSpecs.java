package com.floricultura.api.repository;

import com.floricultura.api.web.dto.ContatoFiltro;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * {@link Specification} compartilhada das listas de contato — {@code cliente} e {@code fornecedor}
 * (SPEC-M6 §3.7, CA-24/CA-25). Uma unica implementacao generica serve aos dois cadastros porque as duas
 * entidades expoem os mesmos atributos ({@code nome}, {@code telefone}, {@code email}, {@code id}) —
 * o contrato dos dois endpoints e identico (D3).
 *
 * <p><b>"Sem telefone/e-mail" = nulo OU vazio apos {@code trim}</b> (R25): o campo e string livre e a
 * string vazia existe na pratica. O mesmo criterio vale no filtro <b>e</b> na ordenacao — por isso a
 * chave de ordenacao por telefone/e-mail e {@code NULLIF(TRIM(campo), '')} com {@code NULLS LAST}:
 * vazio e nulo caem juntos no fim, <b>nas duas direcoes</b> (R27/armadilha 7 do §12).
 *
 * <p><b>O {@code ORDER BY} mora aqui, nao no {@code Pageable}</b>: o {@code Sort} do Spring Data nao
 * expressa a chave {@code NULLIF(TRIM(...))}. O servico passa um {@code Pageable} <b>unsorted</b> de
 * proposito — {@code Sort} presente sobrescreveria este {@code orderBy}. Na query de contagem o
 * {@code orderBy} e ignorado (guarda do {@code resultType} + limpeza do proprio Spring Data).
 */
public final class ContatoSpecs {

    private ContatoSpecs() {
        // Fabrica estatica — nao instanciavel.
    }

    /**
     * Monta o predicado do filtro ({@code nome} ILIKE + tri-estados de telefone/e-mail, combinados com
     * <b>E</b>) e fixa a ordenacao do §3.7 ({@code NULLS LAST} + desempate {@code nome ASC, id ASC}).
     *
     * <p>O {@code ILIKE} do nome delega ao {@link FiltroTexto} — <b>mesmo</b> helper de
     * {@code ProdutoSpecs}, com {@code %}/{@code _} escapados: buscar "50%" nao pode significar coisas
     * diferentes em {@code /clientes} e em {@code /produtos}.
     */
    public static <T> Specification<T> de(ContatoFiltro filtro) {
        return (root, query, cb) -> {
            List<Predicate> predicados = new ArrayList<>();
            if (filtro.nome() != null) {
                predicados.add(FiltroTexto.contem(cb, root.get("nome"), filtro.nome()));
            }
            if (filtro.comTelefone() != null) {
                predicados.add(preenchido(cb, root.get("telefone"), filtro.comTelefone()));
            }
            if (filtro.comEmail() != null) {
                predicados.add(preenchido(cb, root.get("email"), filtro.comEmail()));
            }
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.orderBy(ordenacao(filtro, root, cb));
            }
            return cb.and(predicados.toArray(new Predicate[0]));
        };
    }

    /**
     * Tri-estado do §3.7: {@code com=true} → campo preenchido; {@code com=false} → nulo ou vazio apos
     * {@code trim} (R25).
     */
    private static Predicate preenchido(CriteriaBuilder cb, Path<String> campo, boolean com) {
        Predicate vazio = cb.or(cb.isNull(campo), cb.equal(cb.trim(campo), ""));
        return com ? vazio.not() : vazio;
    }

    /**
     * {@code ORDER BY} do §3.7: por {@code nome}, ou por {@code telefone}/{@code email} com vazios e
     * nulos no fim ({@code NULLS LAST} nas duas direcoes) e desempate {@code nome ASC}. Toda ordenacao
     * fecha com {@code id ASC} — sem ele a paginacao pode repetir/omitir itens em caso de empate.
     */
    private static <T> List<Order> ordenacao(
            ContatoFiltro filtro, Root<T> root, CriteriaBuilder cb) {
        List<Order> ordens = new ArrayList<>();
        boolean asc = filtro.ascendente();
        if (ContatoFiltro.ORDENAR_POR_PADRAO.equals(filtro.ordenarPor())) {
            Path<String> nome = root.get("nome");
            ordens.add(asc ? cb.asc(nome) : cb.desc(nome));
        } else {
            Path<String> campo = root.get(filtro.ordenarPor());
            var chave = cb.nullif(cb.trim(campo), "");
            ordens.add(asc ? cb.asc(chave, Nulls.LAST) : cb.desc(chave, Nulls.LAST));
            ordens.add(cb.asc(root.get("nome")));
        }
        ordens.add(cb.asc(root.get("id")));
        return ordens;
    }
}
