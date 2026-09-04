package com.floricultura.api.service;

import com.floricultura.api.domain.Fornecedor;
import com.floricultura.api.repository.FornecedorRepository;
import com.floricultura.api.web.dto.FornecedorResponse;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.PaginacaoParams;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de leitura de fornecedores do M5 (T-M5-3, CA-1/CA-2/CA-3 — SPEC-M5 §3.4/§4) — irmao de
 * {@code ClienteService}. So leitura aqui (lista paginada + detalhe); a escrita e da T-M5-5. O RBAC
 * (leitura = USER+ADMIN) e imposto pelo {@code SecurityConfig} (GET cai no {@code
 * anyRequest().authenticated()}).
 *
 * <p>{@link FornecedorRepository} injetado como {@link Lazy} (mesmo padrao de {@code ClienteService}/
 * {@code EventoService}): os smokes do M0 sobem sem JPA — o repo so e resolvido na 1a chamada a
 * {@code /fornecedores}.
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
     * null} — a lista <b>nunca</b> materializa o vinculo N:N (CA-3/AD-SQ-38/44).
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
     * Detalha um fornecedor por id (CA-2), preenchendo {@code produtoIds} com os vinculos lidos por
     * query nativa dedicada (§3.5). Inexistente → {@link FornecedorNaoEncontradoException} (404).
     * Fornecedor sem vinculos → {@code produtoIds = []} (§4.5).
     */
    @Transactional(readOnly = true)
    public FornecedorResponse detalhar(Long id) {
        Fornecedor fornecedor = fornecedorRepository.findById(id)
                .orElseThrow(FornecedorNaoEncontradoException::new);
        return FornecedorResponse.comProdutos(
                fornecedor, fornecedorRepository.findProdutoIdsByFornecedorId(id));
    }
}
