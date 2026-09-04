package com.floricultura.api.service;

import com.floricultura.api.domain.Cliente;
import com.floricultura.api.domain.ClienteFactory;
import com.floricultura.api.repository.ClienteRepository;
import com.floricultura.api.repository.ProdutoRepository;
import com.floricultura.api.web.dto.ClienteRequest;
import com.floricultura.api.web.dto.ClienteResponse;
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
 * Regra de leitura de clientes do M5 (T-M5-2, CA-1/CA-2/CA-3 — SPEC-M5 §3.4/§4). So leitura aqui (lista
 * paginada + detalhe); a escrita (criar/atualizar/deletar + replace-set N:N) e da T-M5-4. O RBAC
 * (leitura = USER+ADMIN) e imposto pelo {@code SecurityConfig} (GET cai no {@code
 * anyRequest().authenticated()}) — este servico so trata a regra de leitura.
 *
 * <p>{@link ClienteRepository} injetado como {@link Lazy} (mesmo padrao de {@code EventoService}/
 * {@code ProdutoService}): os smokes do M0 sobem sem JPA — o repo so e resolvido na 1a chamada a
 * {@code /clientes}.
 *
 * <p><b>Escrita (T-M5-4, CA-4..CA-8):</b> {@code criar}/{@code atualizar}/{@code excluir} com validacao
 * dos {@code produtoIds} <b>antes</b> de qualquer escrita e replace-set do vinculo N:N via
 * {@link ClienteProdutoVinculoService}. O RBAC (escrita = ADMIN) e imposto pelo {@code SecurityConfig}
 * (matchers §3.4).
 */
@Service
public class ClienteService {

    /** Ordenacao da lista (§3.4): {@code nome ASC}. */
    private static final Sort ORDENACAO_PADRAO = Sort.by("nome").ascending();

    private final ClienteRepository clienteRepository;
    private final ProdutoRepository produtoRepository;
    private final ClienteProdutoVinculoService vinculoService;

    public ClienteService(
            @Lazy ClienteRepository clienteRepository,
            @Lazy ProdutoRepository produtoRepository,
            ClienteProdutoVinculoService vinculoService) {
        this.clienteRepository = clienteRepository;
        this.produtoRepository = produtoRepository;
        this.vinculoService = vinculoService;
    }

    /**
     * Lista clientes paginados (CA-1), ordenados por {@code nome ASC}, com filtro opcional {@code ILIKE
     * '%nome%'} (case-insensitive, substring). {@code nome} vazio/em branco = sem filtro. Range de
     * paginacao invalido → {@code 400} (helper §3.4). Cada item vem com {@code produtoIds = null} — a
     * lista <b>nunca</b> materializa o vinculo N:N (CA-3/AD-SQ-38/44).
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
     * Detalha um cliente por id (CA-2), preenchendo {@code produtoIds} com os vinculos lidos por query
     * nativa dedicada (§3.5). Inexistente → {@link ClienteNaoEncontradoException} (404). Cliente sem
     * vinculos → {@code produtoIds = []} (§4.5).
     */
    @Transactional(readOnly = true)
    public ClienteResponse detalhar(Long id) {
        Cliente cliente = clienteRepository.findById(id)
                .orElseThrow(ClienteNaoEncontradoException::new);
        return ClienteResponse.comProdutos(cliente, clienteRepository.findProdutoIdsByClienteId(id));
    }

    /**
     * Cria um cliente (CA-4). Valida os {@code produtoIds} <b>antes</b> de qualquer escrita (CA-8: id
     * inexistente → 400 {@code field=produtoIds}, nada persiste). {@code produtoIds} ausente/{@code null}
     * = sem vinculos; presente (inclusive {@code []}) = replace-set. Devolve o {@link ClienteResponse}
     * com {@code produtoIds} preenchido (§3.3).
     *
     * <p><b>Sem {@code @Transactional} aqui de proposito</b> (mesmo motivo de {@code ProdutoService.
     * criar}): com {@code open-in-view=false}, o {@code save} e a releitura correm em contextos de
     * persistencia distintos, e so a releitura traz {@code criado_em}/{@code atualizado_em} (colunas
     * {@code insertable=false}, do {@code DEFAULT now()} do banco). A validacao dos {@code produtoIds}
     * roda antes do {@code save}, entao o id inexistente barra a criacao (nada persiste — CA-8).
     */
    public ClienteResponse criar(ClienteRequest req) {
        List<Long> produtoIds = normalizarEValidarProdutos(req.produtoIds());
        Cliente cliente = ClienteFactory.novo(
                req.nome(), req.telefone(), req.email(), req.observacoes());
        Long id = clienteRepository.save(cliente).getId();
        if (produtoIds != null) {
            vinculoService.substituir(id, produtoIds); // replace-set atomico (transacao propria)
        }
        Cliente salvo = clienteRepository.findById(id)
                .orElseThrow(ClienteNaoEncontradoException::new);
        return ClienteResponse.comProdutos(salvo, clienteRepository.findProdutoIdsByClienteId(id));
    }

    /**
     * Atualiza um cliente (CA-5/CA-7). Valida os {@code produtoIds} <b>antes</b> de qualquer escrita
     * (CA-8, dentro desta transacao → rollback se falhar). Inexistente →
     * {@link ClienteNaoEncontradoException} (404). Estampa {@code atualizado_em = now()} (§4.1).
     * {@code produtoIds} ausente/{@code null} = <b>nao altera</b> os vinculos (update parcial); presente
     * (inclusive {@code []}) = replace-set.
     */
    @Transactional
    public ClienteResponse atualizar(Long id, ClienteRequest req) {
        List<Long> produtoIds = normalizarEValidarProdutos(req.produtoIds());
        Cliente cliente = clienteRepository.findById(id)
                .orElseThrow(ClienteNaoEncontradoException::new);
        cliente.setNome(req.nome());
        cliente.setTelefone(req.telefone());
        cliente.setEmail(req.email());
        cliente.setObservacoes(req.observacoes());
        cliente.setAtualizadoEm(Instant.now());
        clienteRepository.save(cliente);
        if (produtoIds != null) {
            vinculoService.substituir(id, produtoIds); // replace-set (junta esta transacao)
        }
        return ClienteResponse.comProdutos(cliente, clienteRepository.findProdutoIdsByClienteId(id));
    }

    /**
     * Hard delete de cliente (CA-6, FC-08 — IRREVERSIVEL). Inexistente →
     * {@link ClienteNaoEncontradoException} (404). O {@code ON DELETE CASCADE} da V9 remove as linhas de
     * {@code cliente_produto} desse cliente no banco; os produtos permanecem. A confirmacao e do front.
     */
    @Transactional
    public void excluir(Long id) {
        if (!clienteRepository.existsById(id)) {
            throw new ClienteNaoEncontradoException();
        }
        clienteRepository.deleteById(id);
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
