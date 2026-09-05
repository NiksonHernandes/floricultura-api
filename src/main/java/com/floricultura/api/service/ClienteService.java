package com.floricultura.api.service;

import com.floricultura.api.domain.Cliente;
import com.floricultura.api.domain.ClienteFactory;
import com.floricultura.api.repository.ClienteRepository;
import com.floricultura.api.web.dto.ClienteRequest;
import com.floricultura.api.web.dto.ClienteResponse;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.PaginacaoParams;
import java.time.Instant;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de negocio de clientes do M5 (SPEC-M5 §3.4/§4). Leitura (lista paginada + detalhe) e escrita
 * (criar/atualizar/excluir). O RBAC (leitura = USER+ADMIN; escrita = ADMIN) e imposto pelo
 * {@code SecurityConfig} (GET cai no {@code anyRequest().authenticated()}; POST/PUT/DELETE nos matchers
 * §3.4).
 *
 * <p>{@link ClienteRepository} injetado como {@link Lazy} (mesmo padrao de {@code EventoService}/
 * {@code ProdutoService}): os smokes do M0 sobem sem JPA — o repo so e resolvido na 1a chamada a
 * {@code /clientes}.
 *
 * <p><b>Revisao 2026-09-04 (AD-SQ-65):</b> o vinculo cliente↔produto deixou de ser juncao N:N editavel
 * (removidos {@code produtoIds} de escrita, {@code ClienteProdutoVinculoService} e a validacao de
 * {@code produtoIds}) e passa a ser <b>derivado da movimentacao</b> (SAIDAS deste cliente). O
 * {@code produtoIds} do <b>detalhe</b>/POST/PUT vem da query nativa {@code findProdutoIdsByClienteId}
 * (RB-3/R-CA-7); a <b>lista</b> nunca o materializa (AD-SQ-38).
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
     * lista <b>nunca</b> materializa o vinculo (CA-3/AD-SQ-38).
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
     * Detalha um cliente por id (CA-2/R-CA-7), com {@code produtoIds} <b>derivado do ledger</b> (produtos
     * das SAIDAS deste cliente — §R3.4). Inexistente → {@link ClienteNaoEncontradoException} (404).
     * Cliente sem SAIDAS → {@code produtoIds = []}.
     */
    @Transactional(readOnly = true)
    public ClienteResponse detalhar(Long id) {
        Cliente cliente = clienteRepository.findById(id)
                .orElseThrow(ClienteNaoEncontradoException::new);
        return ClienteResponse.comProdutos(cliente, clienteRepository.findProdutoIdsByClienteId(id));
    }

    /**
     * Cria um cliente (CA-4). Devolve o {@link ClienteResponse} recem-criado (§3.3).
     *
     * <p><b>Sem {@code @Transactional} aqui de proposito</b> (mesmo motivo de {@code ProdutoService.
     * criar}): com {@code open-in-view=false}, o {@code save} e a releitura correm em contextos de
     * persistencia distintos, e so a releitura traz {@code criado_em}/{@code atualizado_em} (colunas
     * {@code insertable=false}, do {@code DEFAULT now()} do banco).
     */
    public ClienteResponse criar(ClienteRequest req) {
        Cliente cliente = ClienteFactory.novo(
                req.nome(), req.telefone(), req.email(), req.observacoes());
        Long id = clienteRepository.save(cliente).getId();
        Cliente salvo = clienteRepository.findById(id)
                .orElseThrow(ClienteNaoEncontradoException::new);
        // produtoIds derivado do ledger: cliente recem-criado ainda nao tem SAIDAS ⇒ [] (§R3.4).
        return ClienteResponse.comProdutos(salvo, clienteRepository.findProdutoIdsByClienteId(id));
    }

    /**
     * Atualiza um cliente (CA-5). Inexistente → {@link ClienteNaoEncontradoException} (404). Estampa
     * {@code atualizado_em = now()} (§4.1).
     */
    @Transactional
    public ClienteResponse atualizar(Long id, ClienteRequest req) {
        Cliente cliente = clienteRepository.findById(id)
                .orElseThrow(ClienteNaoEncontradoException::new);
        cliente.setNome(req.nome());
        cliente.setTelefone(req.telefone());
        cliente.setEmail(req.email());
        cliente.setObservacoes(req.observacoes());
        cliente.setAtualizadoEm(Instant.now());
        clienteRepository.save(cliente);
        return ClienteResponse.comProdutos(cliente, clienteRepository.findProdutoIdsByClienteId(id));
    }

    /**
     * Hard delete de cliente (CA-6, FC-08 — IRREVERSIVEL). Inexistente →
     * {@link ClienteNaoEncontradoException} (404). Os produtos permanecem. A confirmacao e do front.
     */
    @Transactional
    public void excluir(Long id) {
        if (!clienteRepository.existsById(id)) {
            throw new ClienteNaoEncontradoException();
        }
        clienteRepository.deleteById(id);
    }
}
