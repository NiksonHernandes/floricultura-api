package com.floricultura.api.service;

import com.floricultura.api.repository.ClienteRepository;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica o <b>replace-set</b> do vinculo N:N cliente↔produto (M5/AD-SQ-44, §3.5/§4.2) numa unica
 * transacao atomica — copia fiel do {@code ProdutoEventoVinculoService}. Bean <b>separado</b> do
 * {@code ClienteService} de proposito: o {@code criar} do cliente roda <b>sem</b> {@code @Transactional}
 * (para reler {@code criado_em} do {@code DEFAULT now()} num contexto de persistencia novo — mesmo motivo
 * do M2/M4); as queries {@code @Modifying} do replace-set exigem uma transacao, entao a delegacao a este
 * bean abre uma transacao propria (propagacao REQUIRED) quando chamado do fluxo nao-transacional, e
 * <b>junta</b> a transacao existente quando chamado do {@code atualizar} (que e {@code @Transactional}).
 */
@Service
public class ClienteProdutoVinculoService {

    private final ClienteRepository clienteRepository;

    public ClienteProdutoVinculoService(@Lazy ClienteRepository clienteRepository) {
        this.clienteRepository = clienteRepository;
    }

    /**
     * Substitui o conjunto de vinculos do cliente (§4.2): apaga todos os links atuais e reinsere os de
     * {@code produtoIds} (ja deduplicados e validados pelo {@code ClienteService}). Lista vazia = cliente
     * sem vinculos. Atomico dentro desta transacao.
     *
     * @param clienteId  id do cliente (ja persistido)
     * @param produtoIds ids dos produtos a vincular (deduplicados; podem ser vazios)
     */
    @Transactional
    public void substituir(Long clienteId, List<Long> produtoIds) {
        clienteRepository.removerVinculosDoCliente(clienteId);
        for (Long produtoId : produtoIds) {
            clienteRepository.inserirVinculo(clienteId, produtoId);
        }
    }
}
