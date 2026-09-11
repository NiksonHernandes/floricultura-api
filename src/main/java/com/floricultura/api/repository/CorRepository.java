package com.floricultura.api.repository;

import com.floricultura.api.domain.Cor;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Acesso a persistencia de {@link Cor} (SPEC-M6 §3.2/§3.2.1).
 *
 * <p><b>Sem {@code IgnoreCase} em lugar nenhum, de proposito</b> (gate item 4c): o valor persistido JA E
 * o canonico (MAIUSCULO), entao a duplicata e detectada por <b>igualdade exata</b>
 * ({@code existsByNome}/{@code existsByNomeAndIdNot}) e o filtro por substring compara canonico contra
 * canonico. {@code uk_cor_nome} (V12) e so a rede da corrida.
 */
@Repository
public interface CorRepository extends JpaRepository<Cor, Long> {

    /** Duplicata no POST (CA-2): igualdade exata sobre o canonico. */
    boolean existsByNome(String nome);

    /** Duplicata no PUT (CA-5): ignora a propria linha — renomear para o proprio canonico nao e conflito. */
    boolean existsByNomeAndIdNot(String nome, Long id);

    /** Filtro {@code ?nome} (CA-39.6): o termo chega CANONIZADO, entao o {@code like} case-sensitive casa. */
    Page<Cor> findByNomeContaining(String nomeCanonico, Pageable pageable);

    /** Contagem de uso de UMA cor (detalhe e guarda do DELETE — CA-6). */
    @Query(value = "SELECT count(*) FROM produto_cor WHERE cor_id = :corId", nativeQuery = true)
    long contarProdutos(@Param("corId") Long corId);

    /**
     * Contagem agrupada da PAGINA (CA-4 — <b>sem N+1</b>): UMA query para todos os ids da pagina; o
     * servico casa em memoria e completa com {@code 0} quem nao aparecer. Cada linha e
     * {@code [cor_id, total]}. O chamador NAO deve invocar com lista vazia ({@code IN ()} e erro de
     * sintaxe no Postgres).
     */
    @Query(value = "SELECT cor_id, count(*) FROM produto_cor WHERE cor_id IN (:corIds) GROUP BY cor_id",
            nativeQuery = true)
    List<Object[]> contarProdutosPorCor(@Param("corIds") Collection<Long> corIds);
}
