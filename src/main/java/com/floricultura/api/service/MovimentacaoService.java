package com.floricultura.api.service;

import com.floricultura.api.domain.MovimentacaoEstoque;
import com.floricultura.api.domain.MovimentacaoFactory;
import com.floricultura.api.domain.Produto;
import com.floricultura.api.repository.MovimentacaoRepository;
import com.floricultura.api.repository.ProdutoRepository;
import com.floricultura.api.web.dto.MovimentacaoRequest;
import com.floricultura.api.web.dto.MovimentacaoResponse;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.PaginacaoParams;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Movimentacao de estoque do M2 (T-M2-4, CA-10/CA-11/CA-12/CA-14 / AD-SQ-30) sobre o ledger imutavel
 * {@link MovimentacaoEstoque} (AD-SQ-8). O RBAC (POST = ADMIN; GET = autenticado) e imposto pelo
 * {@code SecurityConfig} (matcher {@code POST /produtos/**} ja cobre {@code POST .../movimentacoes}) —
 * este servico so trata a regra de negocio.
 *
 * <p><b>Concorrencia (§4/§12):</b> {@link #movimentar} roda numa {@code @Transactional} que primeiro
 * <b>trava a linha do produto</b> via {@link ProdutoRepository#findByIdForUpdate} ({@code SELECT ...
 * FOR UPDATE}). Duas SAIDAS concorrentes serializam nesse lock: a segunda so prossegue apos o commit
 * da primeira e reavalia o {@code estoque_atual} ja atualizado — impossivel ambas passarem na
 * checagem e furarem o estoque para negativo. O CHECK {@code ck_produto_estoque_atual (>=0)} da V1 e a
 * ultima barreira (defesa em profundidade).
 *
 * <p>{@link ProdutoRepository}/{@link MovimentacaoRepository} injetados como {@link Lazy} (mesmo padrao
 * de {@code ProdutoService}/{@code UsuarioService}): os smokes do M0 ({@code contextLoads},
 * {@code HealthCheckControllerTest}) sobem sem JPA — os repos so sao resolvidos na 1a chamada.
 */
@Service
public class MovimentacaoService {

    /** Ordenacao do historico (§3.3): {@code criadoEm DESC} (mais recente primeiro). */
    private static final Sort ORDENACAO_HISTORICO = Sort.by(Sort.Direction.DESC, "criadoEm");

    private static final String ENTRADA = "ENTRADA";
    private static final String SAIDA = "SAIDA";
    private static final String AJUSTE = "AJUSTE";

    private final ProdutoRepository produtoRepository;
    private final MovimentacaoRepository movimentacaoRepository;

    public MovimentacaoService(
            @Lazy ProdutoRepository produtoRepository,
            @Lazy MovimentacaoRepository movimentacaoRepository) {
        this.produtoRepository = produtoRepository;
        this.movimentacaoRepository = movimentacaoRepository;
    }

    /**
     * Registra uma movimentacao (CA-10/CA-11/CA-12). Sob lock pessimista do produto: (1) calcula o novo
     * {@code estoque_atual} conforme o {@code tipo}, <b>validando SAIDA &gt; estoque ANTES de qualquer
     * escrita</b> (nada e gravado se bloquear — estoque nunca negativo); (2) atualiza o snapshot no
     * produto; (3) insere a linha do ledger com {@code produto_nome} (snapshot), {@code
     * quantidade_resultante} e {@code usuario_id} (do {@code @AuthenticationPrincipal}). Produto
     * inexistente → {@link ProdutoNaoEncontradoException} (404).
     *
     * @param produtoId id do produto movimentado
     * @param req       tipo/quantidade/motivo (forma ja validada pelo Bean Validation do DTO)
     * @param usuarioId id do autor autenticado (nunca do payload — §9)
     */
    @Transactional
    public MovimentacaoResponse movimentar(Long produtoId, MovimentacaoRequest req, Long usuarioId) {
        Produto produto = produtoRepository.findByIdForUpdate(produtoId)
                .orElseThrow(ProdutoNaoEncontradoException::new);

        BigDecimal estoqueAtual = produto.getEstoqueAtual();
        BigDecimal quantidade = req.quantidade();
        BigDecimal resultante = calcularEstoqueResultante(req.tipo(), estoqueAtual, quantidade);

        // Escritas so acontecem apos a validacao acima (CA-11: SAIDA insuficiente nao grava nada).
        produto.setEstoqueAtual(resultante);
        produto.setAtualizadoEm(Instant.now());
        produtoRepository.save(produto);

        MovimentacaoEstoque mov = MovimentacaoFactory.nova(
                produto.getId(),
                produto.getNome(),
                req.tipo(),
                quantidade,
                resultante,
                req.motivo(),
                usuarioId);
        mov = movimentacaoRepository.saveAndFlush(mov);

        // criado_em vem do DEFAULT now() do banco (coluna insertable=false) — projecao escalar le o
        // valor real gravado nesta mesma transacao (sem EntityManager.refresh; ver o repositorio).
        Instant criadoEm = movimentacaoRepository.findCriadoEmById(mov.getId());
        return MovimentacaoResponse.de(mov, criadoEm);
    }

    /**
     * Aplica a semantica do tipo (AD-SQ-30) e devolve o estoque resultante, <b>sem</b> tocar o banco.
     * {@code ENTRADA} soma; {@code SAIDA} subtrai (bloqueia se maior que o estoque); {@code AJUSTE}
     * define a quantidade-alvo absoluta ({@code 0} = zerar, valido). {@code ENTRADA}/{@code SAIDA} com
     * {@code 0} → {@link QuantidadeInvalidaException} (400).
     */
    private BigDecimal calcularEstoqueResultante(
            String tipo, BigDecimal estoqueAtual, BigDecimal quantidade) {
        switch (tipo) {
            case ENTRADA:
                if (quantidade.signum() <= 0) {
                    throw new QuantidadeInvalidaException(ENTRADA);
                }
                return estoqueAtual.add(quantidade);
            case SAIDA:
                if (quantidade.signum() <= 0) {
                    throw new QuantidadeInvalidaException(SAIDA);
                }
                if (quantidade.compareTo(estoqueAtual) > 0) {
                    throw new EstoqueInsuficienteException(estoqueAtual);
                }
                return estoqueAtual.subtract(quantidade);
            case AJUSTE:
                return quantidade; // alvo absoluto; 0 = zerar (FC-08, nao e delete)
            default:
                // Inatingivel: o @Pattern do MovimentacaoRequest ja restringe a ENTRADA/SAIDA/AJUSTE.
                throw new IllegalArgumentException("Tipo de movimentacao invalido: " + tipo);
        }
    }

    /**
     * Historico paginado de movimentacoes de um produto (CA-14), ordenado por {@code criadoEm DESC}.
     * Produto inexistente → {@link ProdutoNaoEncontradoException} (404). Range de paginacao invalido →
     * {@code 400} (helper §3.3).
     */
    @Transactional(readOnly = true)
    public PaginaResponse<MovimentacaoResponse> historico(
            Long produtoId, Integer pagina, Integer tamanho) {
        if (!produtoRepository.existsById(produtoId)) {
            throw new ProdutoNaoEncontradoException();
        }
        Pageable pageable = PaginacaoParams.paraPageable(pagina, tamanho, ORDENACAO_HISTORICO);
        Page<MovimentacaoEstoque> page =
                movimentacaoRepository.findByProdutoId(produtoId, pageable);
        return PaginaResponse.de(page, MovimentacaoResponse::de);
    }
}
