package com.floricultura.api.service;

import com.floricultura.api.domain.Produto;
import com.floricultura.api.domain.ProdutoFactory;
import com.floricultura.api.repository.EventoRepository;
import com.floricultura.api.repository.ProdutoRepository;
import com.floricultura.api.repository.ReferenciaSimplesProjection;
import com.floricultura.api.web.dto.AtualizarProdutoRequest;
import com.floricultura.api.web.dto.CriarProdutoRequest;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.PaginacaoParams;
import com.floricultura.api.web.dto.ProdutoRelacionamentosResponse;
import com.floricultura.api.web.dto.ProdutoResponse;
import com.floricultura.api.web.dto.ReferenciaSimples;
import java.time.Instant;
import java.util.List;
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
    private final EventoRepository eventoRepository;
    private final ProdutoEventoVinculoService vinculoService;

    public ProdutoService(
            @Lazy ProdutoRepository produtoRepository,
            @Lazy EventoRepository eventoRepository,
            ProdutoEventoVinculoService vinculoService) {
        this.produtoRepository = produtoRepository;
        this.eventoRepository = eventoRepository;
        this.vinculoService = vinculoService;
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
        Produto produto = produtoRepository.findById(id)
                .orElseThrow(ProdutoNaoEncontradoException::new);
        // Detalhe (§3.4/CA-9): carrega os eventoIds vinculados (na lista vem null, evita N+1).
        return ProdutoResponse.deDetalhe(produto, produtoRepository.findEventoIdsByProdutoId(id));
    }

    /**
     * Relacionamentos <b>derivados</b> do produto (M5-revisao/AD-SQ-66, §R3.5, R-CA-10) para o
     * "visualizar produto": eventos vinculados ({@code evento_produto}), fornecedores das ENTRADAS e
     * clientes das SAIDAS — todos por <b>nome</b>, read-only, sem bytea (AD-SQ-38). Inexistente →
     * {@link ProdutoNaoEncontradoException} (404). O JOIN na tabela viva devolve o nome atual e omite
     * cadastros hard-deletados (id nulo no ledger nao casa o INNER JOIN — §R3.5).
     */
    @Transactional(readOnly = true)
    public ProdutoRelacionamentosResponse relacionamentos(Long id) {
        if (!produtoRepository.existsById(id)) {
            throw new ProdutoNaoEncontradoException();
        }
        return new ProdutoRelacionamentosResponse(
                mapearReferencias(produtoRepository.findEventosRelacionados(id)),
                mapearReferencias(produtoRepository.findFornecedoresRelacionados(id)),
                mapearReferencias(produtoRepository.findClientesRelacionados(id)));
    }

    private static List<ReferenciaSimples> mapearReferencias(List<ReferenciaSimplesProjection> proj) {
        return proj.stream().map(p -> new ReferenciaSimples(p.getId(), p.getNome())).toList();
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
        // Valida eventoIds ANTES de qualquer escrita (CA-11: id inexistente → 400, nada persiste).
        List<Long> eventoIds = normalizarEValidarEventos(req.eventoIds());
        // M6/R13: regra cruzada da altura ANTES do banco (CA-10) — senao o CHECK viraria 500 (§12 #15).
        validarAltura(req.caracteristica(), req.alturaCm());
        Produto produto = ProdutoFactory.novo(
                req.nome(),
                req.descricao(),
                req.unidadeMedida(),
                req.estoqueMinimo(),
                req.preco(),
                req.imagemUrl());
        aplicarAtributosBotanicos(produto, req.caracteristica(), req.alturaCm(), req.toxicidade());
        Long id = produtoRepository.save(produto).getId();
        if (eventoIds != null) {
            vinculoService.substituir(id, eventoIds); // replace-set atomico (transacao propria)
        }
        Produto salvo = produtoRepository.findById(id)
                .orElseThrow(ProdutoNaoEncontradoException::new);
        return ProdutoResponse.deDetalhe(salvo, produtoRepository.findEventoIdsByProdutoId(id));
    }

    /**
     * Atualiza os campos de cadastro de um produto (CA-7/CA-9). Inexistente →
     * {@link ProdutoNaoEncontradoException} (404). <b>Nunca toca {@code estoqueAtual}</b> (AD-SQ-30): so
     * altera nome/descricao/unidadeMedida/estoqueMinimo/preco/imagemUrl e estampa
     * {@code atualizado_em = now()} (§4). {@code preco} pode ser {@code null} (CA-9/AD-SQ-28).
     */
    @Transactional
    public ProdutoResponse atualizar(Long id, AtualizarProdutoRequest req) {
        List<Long> eventoIds = normalizarEValidarEventos(req.eventoIds());
        // Escalares sao substituidos por inteiro no PUT, entao o par do payload JA E o estado
        // resultante do update — inclusive no caso "so troquei a caracteristica para MUDA" numa linha
        // que tinha altura: se o payload nao limpar alturaCm, isto e 400 (CA-10), nunca 500.
        validarAltura(req.caracteristica(), req.alturaCm());
        Produto produto = produtoRepository.findById(id)
                .orElseThrow(ProdutoNaoEncontradoException::new);
        produto.setNome(req.nome());
        produto.setDescricao(req.descricao());
        produto.setUnidadeMedida(req.unidadeMedida());
        produto.setEstoqueMinimo(req.estoqueMinimo());
        produto.setPreco(req.preco());
        produto.setImagemUrl(req.imagemUrl());
        aplicarAtributosBotanicos(produto, req.caracteristica(), req.alturaCm(), req.toxicidade());
        produto.setAtualizadoEm(Instant.now()); // §4: PUT avanca atualizado_em; estoqueAtual intacto
        produtoRepository.save(produto);
        if (eventoIds != null) {
            vinculoService.substituir(id, eventoIds); // replace-set (junta esta transacao)
        }
        // Le os eventoIds atuais (query nativa, fora do 1o nivel) para o detalhe da resposta.
        return ProdutoResponse.deDetalhe(produto, produtoRepository.findEventoIdsByProdutoId(id));
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

    /**
     * Regra cruzada da altura (SPEC-M6 §4.3/R13, CA-10), avaliada sobre o <b>estado resultante</b> da
     * operacao e <b>antes</b> de qualquer escrita: {@code alturaCm} so e aceita com
     * {@code caracteristica ∈ {JOVEM, ADULTA}}.
     *
     * <p>Cobre os <b>dois</b> casos do contrato: {@code MUDA + altura} e {@code caracteristica = null +
     * altura} (o buraco da logica ternaria que a AD-SQ-89 fechou no CHECK). Sem altura, qualquer
     * caracteristica passa — inclusive {@code null} (P12: produto pre-M6 e valido). O {@code
     * ck_produto_altura_exige_porte} continua como segunda linha de defesa, mas nao deve ser atingido:
     * se fosse, viraria 500 em vez do 400 do contrato (§12 #15a).
     */
    private static void validarAltura(String caracteristica, Integer alturaCm) {
        if (alturaCm == null) {
            return; // sem altura declarada, nao ha regra cruzada a violar
        }
        if (!"JOVEM".equals(caracteristica) && !"ADULTA".equals(caracteristica)) {
            throw new AlturaSemPorteException();
        }
    }

    /**
     * Aplica os 3 atributos botanicos <b>escalares</b> (§3.3): valor grava, {@code null} limpa — mesma
     * semantica de {@code descricao}/{@code preco} do M2 (e <b>nao</b> a de {@code eventoIds}, que e
     * replace-set). Em particular, {@code toxicidade = null} e o terceiro estado "nao informado"
     * (R8/P2), nao um erro. Chamado depois de {@link #validarAltura}.
     */
    private static void aplicarAtributosBotanicos(
            Produto produto, String caracteristica, Integer alturaCm, String toxicidade) {
        produto.setCaracteristica(caracteristica);
        produto.setAlturaCm(alturaCm);
        produto.setToxicidade(toxicidade);
    }

    /**
     * Normaliza/valida {@code eventoIds} do payload (§3.4/§4.2): {@code null} (campo ausente) →
     * {@code null} = "nao mexer nos vinculos" (update parcial; o M4 sempre envia o campo); presente
     * (inclusive {@code []}) → replace-set. Ids deduplicados (nulos descartados); cada id deve existir,
     * senao → {@link EventoInexistenteException} (400 {@code field=eventoIds}) <b>antes</b> de qualquer
     * escrita (CA-11).
     */
    private List<Long> normalizarEValidarEventos(List<Long> eventoIds) {
        if (eventoIds == null) {
            return null; // campo ausente: nao altera o conjunto de vinculos
        }
        if (eventoIds.isEmpty()) {
            return List.of(); // presente vazio: replace-set para "sem vinculos"
        }
        List<Long> dedup = eventoIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        for (Long eventoId : dedup) {
            if (!eventoRepository.existsById(eventoId)) {
                throw new EventoInexistenteException(eventoId);
            }
        }
        return dedup;
    }
}
