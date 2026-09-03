package com.floricultura.api.service;

import com.floricultura.api.repository.ProdutoImagemProjection;
import com.floricultura.api.repository.ProdutoRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de servir/remover a imagem do produto no banco (M3/T-M3-2, CA-7/CA-8/CA-10). O binario vive
 * <b>fora</b> da {@code @Entity Produto} (AD-SQ-38): a leitura usa a projecao nativa
 * {@link ProdutoRepository#findImagemById} e a remocao o {@code UPDATE} nativo
 * {@link ProdutoRepository#removerImagem} — ambos criados na T-M3-1. O upload/{@code POST} e a
 * validacao/anti-spoofing chegam em T-M3-3; aqui so leitura e remocao.
 *
 * <p>{@link ProdutoRepository} injetado como {@link Lazy} (mesmo padrao de {@code ProdutoService}):
 * preserva os smokes do M0 que sobem sem JPA.
 */
@Service
public class ProdutoImagemService {

    private final ProdutoRepository produtoRepository;

    public ProdutoImagemService(@Lazy ProdutoRepository produtoRepository) {
        this.produtoRepository = produtoRepository;
    }

    /**
     * Carrega o binario + content-type para servir o endpoint dedicado (CA-7). <b>404</b> quando o
     * produto nao existe (projecao vazia) <b>ou</b> existe sem imagem ({@code imagemContentType == null}
     * — pelo CHECK de coerencia da V5, isso implica {@code imagem} tambem nulo) → {@link
     * ImagemNaoEncontradaException} (CA-8), nunca 401. A projecao (native + interface) materializa os
     * bytes na propria query, entao e segura fora da transacao mesmo com {@code open-in-view=false}.
     */
    @Transactional(readOnly = true)
    public ProdutoImagemProjection buscarImagem(Long id) {
        ProdutoImagemProjection imagem = produtoRepository.findImagemById(id)
                .orElseThrow(ImagemNaoEncontradaException::new);
        if (imagem.getImagemContentType() == null) {
            throw new ImagemNaoEncontradaException();
        }
        return imagem;
    }

    /**
     * Remove a imagem do banco (zera bytea + metadados) e bumpa {@code atualizado_em} via o {@code
     * UPDATE} nativo (CA-10). <b>Idempotente:</b> o UPDATE afeta a linha do produto <b>existente</b>
     * tenha ou nao imagem → {@code 1} linha → 204. Produto inexistente → {@code 0} linhas →
     * {@link ProdutoNaoEncontradoException} (404).
     */
    @Transactional
    public void remover(Long id) {
        if (produtoRepository.removerImagem(id) == 0) {
            throw new ProdutoNaoEncontradoException();
        }
    }
}
