package com.floricultura.api.service;

import com.floricultura.api.repository.ProdutoRepository;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replace-set dos atributos <b>multivalorados</b> do produto (SPEC-M6 §3.5/R9): cores
 * ({@code produto_cor}) e necessidade de luz ({@code produto_necessidade_luz}), cada conjunto por
 * {@code DELETE} + {@code INSERT} numa unica transacao atomica. Os valores chegam ja deduplicados e
 * <b>validados</b> pelo {@code ProdutoService} (R10/CA-11); lista vazia = conjunto limpo.
 *
 * <p>Bean <b>separado</b> pelo mesmo motivo do {@link ProdutoEventoVinculoService}: o {@code criar} roda
 * <b>sem</b> {@code @Transactional} e as queries {@code @Modifying} exigem transacao — a delegacao abre
 * transacao propria no POST e <b>junta</b> a do {@code atualizar} no PUT. As juncoes nao sao @Entity
 * (§3.5): e isso que preserva a hidratacao da vitrine sob query nativa (§12 #3).
 */
@Service
public class ProdutoAtributosVinculoService {

    private final ProdutoRepository produtoRepository;

    public ProdutoAtributosVinculoService(@Lazy ProdutoRepository produtoRepository) {
        this.produtoRepository = produtoRepository;
    }

    /** Substitui o conjunto de cores do produto (§3.5/R9). */
    @Transactional
    public void substituirCores(Long produtoId, List<Long> corIds) {
        produtoRepository.removerCoresDoProduto(produtoId);
        for (Long corId : corIds) {
            produtoRepository.inserirCor(produtoId, corId);
        }
    }

    /** Substitui o conjunto de necessidades de luz do produto (§3.5/R9). */
    @Transactional
    public void substituirLuzes(Long produtoId, List<String> luzes) {
        produtoRepository.removerLuzesDoProduto(produtoId);
        for (String luz : luzes) {
            produtoRepository.inserirLuz(produtoId, luz);
        }
    }
}
