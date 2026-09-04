package com.floricultura.api.service;

import com.floricultura.api.repository.ProdutoRepository;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica o <b>replace-set</b> do vinculo N:N produto↔evento (M4/AD-SQ-44, §4.2) numa unica transacao
 * atomica. Bean <b>separado</b> do {@code ProdutoService} de proposito: o {@code criar} do produto
 * roda <b>sem</b> {@code @Transactional} (para reler {@code criado_em} do {@code DEFAULT now()} num
 * contexto de persistencia novo — mesmo motivo do M2); as queries {@code @Modifying} do replace-set
 * exigem uma transacao, entao a delegacao a este bean abre uma transacao propria (propagacao REQUIRED)
 * quando chamado do fluxo nao-transacional, e <b>junta</b> a transacao existente quando chamado do
 * {@code atualizar} (que e {@code @Transactional}).
 */
@Service
public class ProdutoEventoVinculoService {

    private final ProdutoRepository produtoRepository;

    public ProdutoEventoVinculoService(@Lazy ProdutoRepository produtoRepository) {
        this.produtoRepository = produtoRepository;
    }

    /**
     * Substitui o conjunto de vinculos do produto (§4.2): apaga todos os links atuais e reinsere os de
     * {@code eventoIds} (ja deduplicados e validados pelo {@code ProdutoService}). Lista vazia = produto
     * sem vinculos. Atomico dentro desta transacao.
     *
     * @param produtoId id do produto (ja persistido)
     * @param eventoIds ids dos eventos a vincular (deduplicados; podem ser vazios)
     */
    @Transactional
    public void substituir(Long produtoId, List<Long> eventoIds) {
        produtoRepository.removerVinculosDoProduto(produtoId);
        for (Long eventoId : eventoIds) {
            produtoRepository.inserirVinculo(produtoId, eventoId);
        }
    }
}
