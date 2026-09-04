package com.floricultura.api.repository;

import com.floricultura.api.domain.Produto;
import jakarta.persistence.LockModeType;
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

    // ----- Vinculo N:N produto<->evento (M4/AD-SQ-44): replace-set por queries nativas dedicadas. -----

    /** Ids dos eventos vinculados ao produto (detalhe {@code GET /{id}} — §3.4/CA-9), ordenados. */
    @Query(value = "SELECT evento_id FROM evento_produto WHERE produto_id = :produtoId "
            + "ORDER BY evento_id", nativeQuery = true)
    List<Long> findEventoIdsByProdutoId(@Param("produtoId") Long produtoId);

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
}
