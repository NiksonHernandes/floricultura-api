package com.floricultura.api.service;

import com.floricultura.api.domain.Fornecedor;
import com.floricultura.api.domain.FornecedorFactory;
import com.floricultura.api.repository.FornecedorRepository;
import com.floricultura.api.web.dto.FornecedorRequest;
import com.floricultura.api.web.dto.FornecedorResponse;
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
 * Regra de negocio de fornecedores do M5 (SPEC-M5 §3.4/§4) — irmao de {@code ClienteService}. Leitura
 * (lista paginada + detalhe) e escrita (criar/atualizar/excluir). O RBAC (leitura = USER+ADMIN; escrita
 * = ADMIN) e imposto pelo {@code SecurityConfig} (GET cai no {@code anyRequest().authenticated()};
 * POST/PUT/DELETE nos matchers §3.4).
 *
 * <p>{@link FornecedorRepository} injetado como {@link Lazy} (mesmo padrao de {@code ClienteService}/
 * {@code EventoService}): os smokes do M0 sobem sem JPA — o repo so e resolvido na 1a chamada a
 * {@code /fornecedores}.
 *
 * <p><b>Revisao 2026-09-04 (AD-SQ-65):</b> o vinculo fornecedor↔produto deixou de ser juncao N:N
 * editavel (removidos {@code produtoIds} de escrita, {@code FornecedorProdutoVinculoService} e a
 * validacao de {@code produtoIds}) e passa a ser <b>derivado da movimentacao</b> (ENTRADAS deste
 * fornecedor). O {@code produtoIds} do <b>detalhe</b>/POST/PUT vem da query nativa
 * {@code findProdutoIdsByFornecedorId} (RB-3/R-CA-7); a <b>lista</b> nunca o materializa (AD-SQ-38).
 */
@Service
public class FornecedorService {

    /** Ordenacao da lista (§3.4): {@code nome ASC}. */
    private static final Sort ORDENACAO_PADRAO = Sort.by("nome").ascending();

    private final FornecedorRepository fornecedorRepository;

    public FornecedorService(@Lazy FornecedorRepository fornecedorRepository) {
        this.fornecedorRepository = fornecedorRepository;
    }

    /**
     * Lista fornecedores paginados (CA-1), ordenados por {@code nome ASC}, com filtro opcional
     * {@code ILIKE '%nome%'} (case-insensitive, substring). {@code nome} vazio/em branco = sem filtro.
     * Range de paginacao invalido → {@code 400} (helper §3.4). Cada item vem com {@code produtoIds =
     * null} — a lista <b>nunca</b> materializa o vinculo (CA-3/AD-SQ-38).
     */
    @Transactional(readOnly = true)
    public PaginaResponse<FornecedorResponse> listar(Integer pagina, Integer tamanho, String nome) {
        Pageable pageable = PaginacaoParams.paraPageable(pagina, tamanho, ORDENACAO_PADRAO);
        Page<Fornecedor> page = (nome == null || nome.isBlank())
                ? fornecedorRepository.findAll(pageable)
                : fornecedorRepository.findByNomeContainingIgnoreCase(nome.trim(), pageable);
        return PaginaResponse.de(page, FornecedorResponse::de);
    }

    /**
     * Detalha um fornecedor por id (CA-2/R-CA-7), com {@code produtoIds} <b>derivado do ledger</b>
     * (produtos das ENTRADAS deste fornecedor — §R3.4). Inexistente →
     * {@link FornecedorNaoEncontradoException} (404). Fornecedor sem ENTRADAS → {@code produtoIds = []}.
     */
    @Transactional(readOnly = true)
    public FornecedorResponse detalhar(Long id) {
        Fornecedor fornecedor = fornecedorRepository.findById(id)
                .orElseThrow(FornecedorNaoEncontradoException::new);
        return FornecedorResponse.comProdutos(
                fornecedor, fornecedorRepository.findProdutoIdsByFornecedorId(id));
    }

    /**
     * Cria um fornecedor (CA-4). Devolve o {@link FornecedorResponse} recem-criado (§3.3).
     *
     * <p><b>Sem {@code @Transactional} aqui de proposito</b> (mesmo motivo de {@code ProdutoService.
     * criar}): so a releitura traz {@code criado_em}/{@code atualizado_em} (colunas {@code
     * insertable=false}, do {@code DEFAULT now()} do banco).
     */
    public FornecedorResponse criar(FornecedorRequest req) {
        Fornecedor fornecedor = FornecedorFactory.novo(
                req.nome(), req.telefone(), req.email(), req.observacoes());
        Long id = fornecedorRepository.save(fornecedor).getId();
        Fornecedor salvo = fornecedorRepository.findById(id)
                .orElseThrow(FornecedorNaoEncontradoException::new);
        // produtoIds derivado do ledger: fornecedor recem-criado ainda nao tem ENTRADAS ⇒ [] (§R3.4).
        return FornecedorResponse.comProdutos(
                salvo, fornecedorRepository.findProdutoIdsByFornecedorId(id));
    }

    /**
     * Atualiza um fornecedor (CA-5). Inexistente → {@link FornecedorNaoEncontradoException} (404).
     * Estampa {@code atualizado_em = now()} (§4.1).
     */
    @Transactional
    public FornecedorResponse atualizar(Long id, FornecedorRequest req) {
        Fornecedor fornecedor = fornecedorRepository.findById(id)
                .orElseThrow(FornecedorNaoEncontradoException::new);
        fornecedor.setNome(req.nome());
        fornecedor.setTelefone(req.telefone());
        fornecedor.setEmail(req.email());
        fornecedor.setObservacoes(req.observacoes());
        fornecedor.setAtualizadoEm(Instant.now());
        fornecedorRepository.save(fornecedor);
        return FornecedorResponse.comProdutos(
                fornecedor, fornecedorRepository.findProdutoIdsByFornecedorId(id));
    }

    /**
     * Hard delete de fornecedor (CA-6, FC-08 — IRREVERSIVEL). Inexistente →
     * {@link FornecedorNaoEncontradoException} (404). Os produtos permanecem. A confirmacao e do front.
     */
    @Transactional
    public void excluir(Long id) {
        if (!fornecedorRepository.existsById(id)) {
            throw new FornecedorNaoEncontradoException();
        }
        fornecedorRepository.deleteById(id);
    }
}
