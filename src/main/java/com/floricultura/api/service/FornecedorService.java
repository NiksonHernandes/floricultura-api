package com.floricultura.api.service;

import com.floricultura.api.domain.Fornecedor;
import com.floricultura.api.domain.FornecedorFactory;
import com.floricultura.api.repository.FornecedorRepository;
import com.floricultura.api.repository.ProdutoRepository;
import com.floricultura.api.web.dto.FornecedorRequest;
import com.floricultura.api.web.dto.FornecedorResponse;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.PaginacaoParams;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
 *
 * <p><b>Escrita (T-M5-5, CA-4..CA-8):</b> {@code criar}/{@code atualizar}/{@code excluir} com validacao
 * dos {@code produtoIds} <b>antes</b> de qualquer escrita e replace-set do vinculo N:N via
 * {@link FornecedorProdutoVinculoService}. O RBAC (escrita = ADMIN) e imposto pelo {@code SecurityConfig}
 * (matchers §3.4).
 */
@Service
public class FornecedorService {

    /** Ordenacao da lista (§3.4): {@code nome ASC}. */
    private static final Sort ORDENACAO_PADRAO = Sort.by("nome").ascending();

    private final FornecedorRepository fornecedorRepository;
    private final ProdutoRepository produtoRepository;
    private final FornecedorProdutoVinculoService vinculoService;

    public FornecedorService(
            @Lazy FornecedorRepository fornecedorRepository,
            @Lazy ProdutoRepository produtoRepository,
            FornecedorProdutoVinculoService vinculoService) {
        this.fornecedorRepository = fornecedorRepository;
        this.produtoRepository = produtoRepository;
        this.vinculoService = vinculoService;
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

    /**
     * Cria um fornecedor (CA-4). Valida os {@code produtoIds} <b>antes</b> de qualquer escrita (CA-8: id
     * inexistente → 400 {@code field=produtoIds}, nada persiste). {@code produtoIds} ausente/{@code null}
     * = sem vinculos; presente (inclusive {@code []}) = replace-set. Devolve o {@link FornecedorResponse}
     * com {@code produtoIds} preenchido (§3.3).
     *
     * <p><b>Sem {@code @Transactional} aqui de proposito</b> (mesmo motivo de {@code ProdutoService.
     * criar}): so a releitura traz {@code criado_em}/{@code atualizado_em} (colunas {@code
     * insertable=false}, do {@code DEFAULT now()} do banco). A validacao dos {@code produtoIds} roda antes
     * do {@code save}, entao o id inexistente barra a criacao (nada persiste — CA-8).
     */
    public FornecedorResponse criar(FornecedorRequest req) {
        List<Long> produtoIds = normalizarEValidarProdutos(req.produtoIds());
        Fornecedor fornecedor = FornecedorFactory.novo(
                req.nome(), req.telefone(), req.email(), req.observacoes());
        Long id = fornecedorRepository.save(fornecedor).getId();
        if (produtoIds != null) {
            vinculoService.substituir(id, produtoIds); // replace-set atomico (transacao propria)
        }
        Fornecedor salvo = fornecedorRepository.findById(id)
                .orElseThrow(FornecedorNaoEncontradoException::new);
        return FornecedorResponse.comProdutos(
                salvo, fornecedorRepository.findProdutoIdsByFornecedorId(id));
    }

    /**
     * Atualiza um fornecedor (CA-5/CA-7). Valida os {@code produtoIds} <b>antes</b> de qualquer escrita
     * (CA-8, dentro desta transacao → rollback se falhar). Inexistente →
     * {@link FornecedorNaoEncontradoException} (404). Estampa {@code atualizado_em = now()} (§4.1).
     * {@code produtoIds} ausente/{@code null} = <b>nao altera</b> os vinculos (update parcial); presente
     * (inclusive {@code []}) = replace-set.
     */
    @Transactional
    public FornecedorResponse atualizar(Long id, FornecedorRequest req) {
        List<Long> produtoIds = normalizarEValidarProdutos(req.produtoIds());
        Fornecedor fornecedor = fornecedorRepository.findById(id)
                .orElseThrow(FornecedorNaoEncontradoException::new);
        fornecedor.setNome(req.nome());
        fornecedor.setTelefone(req.telefone());
        fornecedor.setEmail(req.email());
        fornecedor.setObservacoes(req.observacoes());
        fornecedor.setAtualizadoEm(Instant.now());
        fornecedorRepository.save(fornecedor);
        if (produtoIds != null) {
            vinculoService.substituir(id, produtoIds); // replace-set (junta esta transacao)
        }
        return FornecedorResponse.comProdutos(
                fornecedor, fornecedorRepository.findProdutoIdsByFornecedorId(id));
    }

    /**
     * Hard delete de fornecedor (CA-6, FC-08 — IRREVERSIVEL). Inexistente →
     * {@link FornecedorNaoEncontradoException} (404). O {@code ON DELETE CASCADE} da V9 remove as linhas de
     * {@code fornecedor_produto} desse fornecedor no banco; os produtos permanecem. A confirmacao e do
     * front.
     */
    @Transactional
    public void excluir(Long id) {
        if (!fornecedorRepository.existsById(id)) {
            throw new FornecedorNaoEncontradoException();
        }
        fornecedorRepository.deleteById(id);
    }

    /**
     * Normaliza/valida {@code produtoIds} do payload (§3.5/§4.2): {@code null} (campo ausente) →
     * {@code null} = "nao mexer nos vinculos" (update parcial; o front sempre envia o campo); presente
     * (inclusive {@code []}) → replace-set. Ids deduplicados (nulos descartados); cada id deve existir em
     * {@code produto}, senao → {@link ProdutoInexistenteException} (400 {@code field=produtoIds})
     * <b>antes</b> de qualquer escrita (CA-8).
     */
    private List<Long> normalizarEValidarProdutos(List<Long> produtoIds) {
        if (produtoIds == null) {
            return null; // campo ausente: nao altera o conjunto de vinculos
        }
        if (produtoIds.isEmpty()) {
            return List.of(); // presente vazio: replace-set para "sem vinculos"
        }
        List<Long> dedup = produtoIds.stream().filter(Objects::nonNull).distinct().toList();
        for (Long produtoId : dedup) {
            if (!produtoRepository.existsById(produtoId)) {
                throw new ProdutoInexistenteException(produtoId);
            }
        }
        return dedup;
    }
}
