package com.floricultura.api.service;

import com.floricultura.api.domain.Produto;
import com.floricultura.api.repository.ProdutoRepository;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.PaginacaoParams;
import com.floricultura.api.web.dto.ProdutoResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de leitura de produtos do M2 — listar paginado (+ filtro por nome) e detalhar (SPEC-M2 §3.2/
 * §3.3, CA-2/CA-3/CA-4/CA-5/CA-15). A escrita (POST/PUT/DELETE) e a movimentacao chegam em T-M2-3/
 * T-M2-4. O RBAC (GET autenticado; escrita ADMIN) e imposto pelo {@code SecurityConfig} — este
 * servico so trata a regra de negocio.
 *
 * <p>{@link ProdutoRepository} injetado como {@link Lazy} (mesmo padrao do {@code UsuarioService}):
 * os smokes do M0 ({@code contextLoads}, {@code HealthCheckControllerTest}) sobem sem JPA — o repo so
 * e resolvido na primeira chamada a {@code /produtos}, preservando aquelas suites.
 */
@Service
public class ProdutoService {

    /** Ordenacao MVP da lista de produtos (§3.3): {@code nome ASC}; o helper nasce extensivel. */
    private static final Sort ORDENACAO_PADRAO = Sort.by(Sort.Direction.ASC, "nome");

    private final ProdutoRepository produtoRepository;

    public ProdutoService(@Lazy ProdutoRepository produtoRepository) {
        this.produtoRepository = produtoRepository;
    }

    /**
     * Lista produtos paginados (CA-2/CA-3), ordenados por {@code nome ASC}, com filtro opcional
     * {@code ILIKE '%nome%'} (case-insensitive, substring — CA-4). {@code nome} vazio/em branco = sem
     * filtro. Parametros de paginacao fora do range → {@code 400} (helper §3.3). Pagina alem do total
     * → pagina vazia com {@code ultima=true} (§4).
     */
    @Transactional(readOnly = true)
    public PaginaResponse<ProdutoResponse> listar(Integer pagina, Integer tamanho, String nome) {
        Pageable pageable = PaginacaoParams.paraPageable(pagina, tamanho, ORDENACAO_PADRAO);
        Page<Produto> page = (nome == null || nome.isBlank())
                ? produtoRepository.findAll(pageable)
                : produtoRepository.findByNomeContainingIgnoreCase(nome.trim(), pageable);
        return PaginaResponse.de(page, ProdutoResponse::de);
    }

    /**
     * Detalha um produto por id (CA-5), com {@code estoqueBaixo} computado (CA-15). Inexistente →
     * {@link ProdutoNaoEncontradoException} (404).
     */
    @Transactional(readOnly = true)
    public ProdutoResponse detalhar(Long id) {
        return produtoRepository.findById(id)
                .map(ProdutoResponse::de)
                .orElseThrow(ProdutoNaoEncontradoException::new);
    }
}
