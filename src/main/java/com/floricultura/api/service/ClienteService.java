package com.floricultura.api.service;

import com.floricultura.api.domain.Cliente;
import com.floricultura.api.repository.ClienteRepository;
import com.floricultura.api.web.dto.ClienteResponse;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.PaginacaoParams;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de leitura de clientes do M5 (T-M5-2, CA-1/CA-2/CA-3 — SPEC-M5 §3.4/§4). So leitura aqui (lista
 * paginada + detalhe); a escrita (criar/atualizar/deletar + replace-set N:N) e da T-M5-4. O RBAC
 * (leitura = USER+ADMIN) e imposto pelo {@code SecurityConfig} (GET cai no {@code
 * anyRequest().authenticated()}) — este servico so trata a regra de leitura.
 *
 * <p>{@link ClienteRepository} injetado como {@link Lazy} (mesmo padrao de {@code EventoService}/
 * {@code ProdutoService}): os smokes do M0 sobem sem JPA — o repo so e resolvido na 1a chamada a
 * {@code /clientes}.
 */
@Service
public class ClienteService {

    /** Ordenacao da lista (§3.4): {@code nome ASC}. */
    private static final Sort ORDENACAO_PADRAO = Sort.by("nome").ascending();

    private final ClienteRepository clienteRepository;

    public ClienteService(@Lazy ClienteRepository clienteRepository) {
        this.clienteRepository = clienteRepository;
    }

    /**
     * Lista clientes paginados (CA-1), ordenados por {@code nome ASC}, com filtro opcional {@code ILIKE
     * '%nome%'} (case-insensitive, substring). {@code nome} vazio/em branco = sem filtro. Range de
     * paginacao invalido → {@code 400} (helper §3.4). Cada item vem com {@code produtoIds = null} — a
     * lista <b>nunca</b> materializa o vinculo N:N (CA-3/AD-SQ-38/44).
     */
    @Transactional(readOnly = true)
    public PaginaResponse<ClienteResponse> listar(Integer pagina, Integer tamanho, String nome) {
        Pageable pageable = PaginacaoParams.paraPageable(pagina, tamanho, ORDENACAO_PADRAO);
        Page<Cliente> page = (nome == null || nome.isBlank())
                ? clienteRepository.findAll(pageable)
                : clienteRepository.findByNomeContainingIgnoreCase(nome.trim(), pageable);
        return PaginaResponse.de(page, ClienteResponse::de);
    }

    /**
     * Detalha um cliente por id (CA-2), preenchendo {@code produtoIds} com os vinculos lidos por query
     * nativa dedicada (§3.5). Inexistente → {@link ClienteNaoEncontradoException} (404). Cliente sem
     * vinculos → {@code produtoIds = []} (§4.5).
     */
    @Transactional(readOnly = true)
    public ClienteResponse detalhar(Long id) {
        Cliente cliente = clienteRepository.findById(id)
                .orElseThrow(ClienteNaoEncontradoException::new);
        return ClienteResponse.comProdutos(cliente, clienteRepository.findProdutoIdsByClienteId(id));
    }
}
