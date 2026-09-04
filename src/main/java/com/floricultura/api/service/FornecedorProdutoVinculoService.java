package com.floricultura.api.service;

import com.floricultura.api.repository.FornecedorRepository;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica o <b>replace-set</b> do vinculo N:N fornecedor↔produto (M5/AD-SQ-44, §3.5/§4.2) numa unica
 * transacao atomica — irmao de {@code ClienteProdutoVinculoService}, copia fiel do
 * {@code ProdutoEventoVinculoService}. Bean <b>separado</b> do {@code FornecedorService} de proposito: o
 * {@code criar} do fornecedor roda <b>sem</b> {@code @Transactional} (para reler {@code criado_em} do
 * {@code DEFAULT now()} num contexto de persistencia novo); as queries {@code @Modifying} do replace-set
 * exigem uma transacao, entao a delegacao a este bean abre uma transacao propria quando chamado do fluxo
 * nao-transacional, e <b>junta</b> a transacao existente quando chamado do {@code atualizar}.
 */
@Service
public class FornecedorProdutoVinculoService {

    private final FornecedorRepository fornecedorRepository;

    public FornecedorProdutoVinculoService(@Lazy FornecedorRepository fornecedorRepository) {
        this.fornecedorRepository = fornecedorRepository;
    }

    /**
     * Substitui o conjunto de vinculos do fornecedor (§4.2): apaga todos os links atuais e reinsere os de
     * {@code produtoIds} (ja deduplicados e validados pelo {@code FornecedorService}). Lista vazia =
     * fornecedor sem vinculos. Atomico dentro desta transacao.
     *
     * @param fornecedorId id do fornecedor (ja persistido)
     * @param produtoIds   ids dos produtos a vincular (deduplicados; podem ser vazios)
     */
    @Transactional
    public void substituir(Long fornecedorId, List<Long> produtoIds) {
        fornecedorRepository.removerVinculosDoFornecedor(fornecedorId);
        for (Long produtoId : produtoIds) {
            fornecedorRepository.inserirVinculo(fornecedorId, produtoId);
        }
    }
}
