package com.floricultura.api.service;

import com.floricultura.api.domain.MovimentacaoEstoque;
import com.floricultura.api.domain.MovimentacaoFactory;
import com.floricultura.api.domain.Produto;
import com.floricultura.api.domain.ValoresMovimentacao;
import com.floricultura.api.repository.ClienteRepository;
import com.floricultura.api.repository.FornecedorRepository;
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

    /** Ordenacao vazia: a lista global (§3.5) fixa {@code criado_em DESC} no proprio SQL nativo. */
    private static final Sort SEM_ORDENACAO = Sort.unsorted();

    private final ProdutoRepository produtoRepository;
    private final MovimentacaoRepository movimentacaoRepository;
    private final FornecedorRepository fornecedorRepository;
    private final ClienteRepository clienteRepository;

    public MovimentacaoService(
            @Lazy ProdutoRepository produtoRepository,
            @Lazy MovimentacaoRepository movimentacaoRepository,
            @Lazy FornecedorRepository fornecedorRepository,
            @Lazy ClienteRepository clienteRepository) {
        this.produtoRepository = produtoRepository;
        this.movimentacaoRepository = movimentacaoRepository;
        this.fornecedorRepository = fornecedorRepository;
        this.clienteRepository = clienteRepository;
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
        // Sobrecarga sem nome de autor (usado pelo teste de concorrencia direto): usuario_nome = null.
        return movimentar(produtoId, req, usuarioId, null);
    }

    /**
     * Variante com o <b>nome do autor</b> desnormalizado (V7/AD-SQ-45, CA-19): o {@code usuarioNome} e
     * resolvido do {@code @AuthenticationPrincipal} pelo controller (via {@code
     * UsuarioRepository.findNomeById}) e gravado como snapshot no ledger — <b>nunca</b> vem do payload
     * (§4.5). Demais efeitos identicos a {@link #movimentar(Long, MovimentacaoRequest, Long)}.
     */
    @Transactional
    public MovimentacaoResponse movimentar(
            Long produtoId, MovimentacaoRequest req, Long usuarioId, String usuarioNome) {
        // Contraparte (V10/AD-SQ-64, R-CA-3/4/5): valida tipo×contraparte + existencia e resolve os
        // snapshots de nome ANTES de qualquer escrita — 400 com field, nada persiste.
        Contraparte contraparte = resolverContraparte(req);

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
                usuarioId,
                usuarioNome,
                contraparte.fornecedorId(),
                contraparte.fornecedorNome(),
                contraparte.clienteId(),
                contraparte.clienteNome(),
                ValoresMovimentacao.vazio());
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

    /**
     * Lista GLOBAL paginada do ledger (M4/T-M4-10, CA-22/CA-23 — §3.5/AD-SQ-46). Filtro {@code q}
     * opcional casando {@code produto_nome} <b>OU</b> {@code usuario_nome} por {@code ILIKE '%q%'}
     * (indices GIN trigram da V8); {@code q} vazio/em branco = sem filtro. Ordem {@code criado_em DESC}
     * fixa no SQL nativo — o {@link Pageable} entra sem {@code Sort} (paginacao obrigatoria; range
     * invalido → {@code 400} pelo helper §3.3). Legivel por USER+ADMIN (RBAC no catch-all, sem matcher).
     */
    @Transactional(readOnly = true)
    public PaginaResponse<MovimentacaoResponse> buscarGlobal(
            Integer pagina, Integer tamanho, String q) {
        Pageable pageable = PaginacaoParams.paraPageable(pagina, tamanho, SEM_ORDENACAO);
        String filtro = (q == null || q.isBlank()) ? null : q.trim();
        Page<MovimentacaoEstoque> page = movimentacaoRepository.buscarGlobal(filtro, pageable);
        return PaginaResponse.de(page, MovimentacaoResponse::de);
    }

    /**
     * Valida a contraparte por tipo (§R3.3/R-CA-3/4/5) e resolve o <b>snapshot do nome</b> do cadastro no
     * momento (padrao {@code usuario_nome}/AD-SQ-45 — nunca do payload). Roda <b>antes</b> de qualquer
     * escrita; qualquer violacao lanca {@link ContraparteInvalidaException} (400 com {@code field}),
     * entao nada persiste. Regras:
     * <ul>
     *   <li><b>ENTRADA:</b> {@code clienteId} deve ser {@code null}; {@code fornecedorId} opcional — se
     *       presente, precisa existir (senao 400 {@code field=fornecedorId}).</li>
     *   <li><b>SAIDA:</b> {@code fornecedorId} deve ser {@code null}; {@code clienteId} opcional — se
     *       presente, precisa existir (senao 400 {@code field=clienteId}).</li>
     *   <li><b>AJUSTE:</b> ambos {@code null}.</li>
     * </ul>
     */
    private Contraparte resolverContraparte(MovimentacaoRequest req) {
        Long fornecedorId = req.fornecedorId();
        Long clienteId = req.clienteId();
        switch (req.tipo()) {
            case ENTRADA:
                if (clienteId != null) {
                    throw new ContraparteInvalidaException(
                            "clienteId", "clienteId so e permitido em movimentacao de SAIDA.");
                }
                if (fornecedorId == null) {
                    return Contraparte.vazia();
                }
                String fornecedorNome = fornecedorRepository.findNomeById(fornecedorId);
                if (fornecedorNome == null) {
                    throw new ContraparteInvalidaException(
                            "fornecedorId", "Fornecedor inexistente.");
                }
                return new Contraparte(fornecedorId, fornecedorNome, null, null);
            case SAIDA:
                if (fornecedorId != null) {
                    throw new ContraparteInvalidaException(
                            "fornecedorId", "fornecedorId so e permitido em movimentacao de ENTRADA.");
                }
                if (clienteId == null) {
                    return Contraparte.vazia();
                }
                String clienteNome = clienteRepository.findNomeById(clienteId);
                if (clienteNome == null) {
                    throw new ContraparteInvalidaException("clienteId", "Cliente inexistente.");
                }
                return new Contraparte(null, null, clienteId, clienteNome);
            case AJUSTE:
            default:
                if (fornecedorId != null) {
                    throw new ContraparteInvalidaException(
                            "fornecedorId", "AJUSTE nao aceita contraparte.");
                }
                if (clienteId != null) {
                    throw new ContraparteInvalidaException(
                            "clienteId", "AJUSTE nao aceita contraparte.");
                }
                return Contraparte.vazia();
        }
    }

    /** Contraparte resolvida (id + snapshot do nome) pronta para gravar no ledger — nunca do payload. */
    private record Contraparte(
            Long fornecedorId, String fornecedorNome, Long clienteId, String clienteNome) {

        static Contraparte vazia() {
            return new Contraparte(null, null, null, null);
        }
    }
}
