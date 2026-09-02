package com.floricultura.api.service;

import com.floricultura.api.domain.Produto;
import com.floricultura.api.domain.ProdutoFactory;
import com.floricultura.api.repository.ProdutoRepository;
import com.floricultura.api.web.dto.AtualizarProdutoRequest;
import com.floricultura.api.web.dto.CriarProdutoRequest;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.PaginacaoParams;
import com.floricultura.api.web.dto.ProdutoResponse;
import java.time.Instant;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de produtos do M2 — leitura (listar paginado + detalhar, CA-2/CA-3/CA-4/CA-5/CA-15) e escrita
 * (criar/atualizar/deletar, T-M2-3, CA-6/CA-7/CA-8/CA-9). A movimentacao de estoque chega em T-M2-4. O
 * RBAC (GET autenticado; POST/PUT/DELETE = ADMIN) e imposto pelo {@code SecurityConfig} (matchers por
 * metodo) — este servico so trata a regra de negocio.
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

    /**
     * Cria um produto (CA-6). Nasce com {@code estoqueAtual=0} e {@code ativo=true} via
     * {@link ProdutoFactory} (AD-SQ-30 — estoque so muda por movimentacao). {@code preco} pode ser
     * {@code null} (CA-9/AD-SQ-28). Devolve o {@link ProdutoResponse} com {@code estoqueBaixo}
     * computado.
     *
     * <p><b>Sem {@code @Transactional} aqui de proposito</b> (mesmo motivo do {@code UsuarioService.
     * criar}): com {@code open-in-view=false}, o {@code save} e a releitura correm em contextos de
     * persistencia distintos, e so a releitura traz {@code criado_em}/{@code atualizado_em} (colunas
     * {@code insertable=false}, vindas do {@code DEFAULT now()} do banco — T-M2-1).
     */
    public ProdutoResponse criar(CriarProdutoRequest req) {
        Produto produto = ProdutoFactory.novo(
                req.nome(),
                req.descricao(),
                req.unidadeMedida(),
                req.estoqueMinimo(),
                req.preco(),
                req.imagemUrl());
        Long id = produtoRepository.save(produto).getId();
        return produtoRepository.findById(id)
                .map(ProdutoResponse::de)
                .orElseThrow(ProdutoNaoEncontradoException::new);
    }

    /**
     * Atualiza os campos de cadastro de um produto (CA-7/CA-9). Inexistente →
     * {@link ProdutoNaoEncontradoException} (404). <b>Nunca toca {@code estoqueAtual}</b> (AD-SQ-30): so
     * altera nome/descricao/unidadeMedida/estoqueMinimo/preco/imagemUrl e estampa
     * {@code atualizado_em = now()} (§4). {@code preco} pode ser {@code null} (CA-9/AD-SQ-28).
     */
    @Transactional
    public ProdutoResponse atualizar(Long id, AtualizarProdutoRequest req) {
        Produto produto = produtoRepository.findById(id)
                .orElseThrow(ProdutoNaoEncontradoException::new);
        produto.setNome(req.nome());
        produto.setDescricao(req.descricao());
        produto.setUnidadeMedida(req.unidadeMedida());
        produto.setEstoqueMinimo(req.estoqueMinimo());
        produto.setPreco(req.preco());
        produto.setImagemUrl(req.imagemUrl());
        produto.setAtualizadoEm(Instant.now()); // §4: PUT avanca atualizado_em; estoqueAtual intacto
        return ProdutoResponse.de(produtoRepository.save(produto));
    }

    /**
     * Hard delete de produto (CA-8, FC-08 — IRREVERSIVEL). Inexistente →
     * {@link ProdutoNaoEncontradoException} (404). A confirmacao e responsabilidade do front; o back so
     * executa. Remove <b>apenas</b> a linha do produto: o {@code ON DELETE SET NULL} da FK (V1) roda no
     * banco e anula {@code produto_id} no ledger — a trigger refinada na V4/AD-SQ-34 permite exatamente
     * esse UPDATE, preservando {@code produto_nome} (o ledger sobrevive). A entity {@link Produto} nao
     * mapeia colecao de movimentacoes, entao o Hibernate emite so o {@code DELETE FROM produto}.
     */
    @Transactional
    public void deletar(Long id) {
        if (!produtoRepository.existsById(id)) {
            throw new ProdutoNaoEncontradoException();
        }
        produtoRepository.deleteById(id);
    }
}
