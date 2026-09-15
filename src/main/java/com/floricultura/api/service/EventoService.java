package com.floricultura.api.service;

import com.floricultura.api.domain.Evento;
import com.floricultura.api.domain.EventoFactory;
import com.floricultura.api.domain.Produto;
import com.floricultura.api.repository.EventoRepository;
import com.floricultura.api.repository.ProdutoRepository;
import com.floricultura.api.web.dto.EventoRequest;
import com.floricultura.api.web.dto.EventoResponse;
import com.floricultura.api.web.dto.PaginaResponse;
import com.floricultura.api.web.dto.PaginacaoParams;
import com.floricultura.api.web.dto.ProdutoResponse;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de eventos do M4 (T-M4-2, CA-1..CA-8 — SPEC-M4 §3.2/§4.1). CRUD com validacao cruzada de datas
 * ({@code dataFim >= dataInicio}) e hard delete (FC-08; o {@code ON DELETE CASCADE} da V6 limpa
 * {@code evento_produto}). O RBAC (escrita = ADMIN; leitura = USER+ADMIN) e imposto pelo
 * {@code SecurityConfig} (matchers §3.6) — este servico so trata a regra de negocio.
 *
 * <p>{@link EventoRepository} injetado como {@link Lazy} (mesmo padrao de {@code ProdutoService}/
 * {@code UsuarioService}): os smokes do M0 sobem sem JPA — o repo so e resolvido na 1a chamada a
 * {@code /eventos}.
 */
@Service
public class EventoService {

    /** Ordenacao da lista (§3.2): {@code dataInicio ASC, nome ASC} (desempate estavel). */
    private static final Sort ORDENACAO_PADRAO =
            Sort.by(Sort.Order.asc("dataInicio"), Sort.Order.asc("nome"));

    /** Ordenacao da vitrine (SPEC-M4.1 §3.1): {@code nome ASC} (aplicado a query nativa). */
    private static final Sort ORDENACAO_VITRINE = Sort.by("nome").ascending();

    private final EventoRepository eventoRepository;
    private final ProdutoRepository produtoRepository;

    public EventoService(
            @Lazy EventoRepository eventoRepository,
            @Lazy ProdutoRepository produtoRepository) {
        this.eventoRepository = eventoRepository;
        this.produtoRepository = produtoRepository;
    }

    /**
     * Lista eventos paginados (CA-4), ordenados por {@code dataInicio ASC, nome ASC}, com filtro
     * opcional {@code ILIKE '%nome%'} (case-insensitive, substring). {@code nome} vazio/em branco = sem
     * filtro. Range de paginacao invalido → {@code 400} (helper §3.3).
     */
    @Transactional(readOnly = true)
    public PaginaResponse<EventoResponse> listar(Integer pagina, Integer tamanho, String nome) {
        Pageable pageable = PaginacaoParams.paraPageable(pagina, tamanho, ORDENACAO_PADRAO);
        Page<Evento> page = (nome == null || nome.isBlank())
                ? eventoRepository.findAll(pageable)
                : eventoRepository.findByNomeContainingIgnoreCase(nome.trim(), pageable);
        return PaginaResponse.de(page, EventoResponse::de);
    }

    /**
     * Lista paginada dos produtos vinculados ao evento (vitrine — SPEC-M4.1 §3.1/§4.1, CA-1/CA-2).
     * Valida a existencia do evento <b>antes</b> de paginar → {@link EventoNaoEncontradoException}
     * (404) se inexistente (nao devolve pagina vazia — ancora #2). Ordem {@code nome ASC} via
     * {@link PaginacaoParams} (range invalido → 400 — ancora #1). Evento existente sem produtos →
     * {@code 200} com {@code conteudo=[]}, {@code totalElementos=0} (CA-2). Itens na variante de lista
     * ({@code eventoIds=null}, sem {@code bytea} — AD-SQ-38/ancora #3) e {@code sazonal=true} fixo (PA#2).
     */
    @Transactional(readOnly = true)
    public PaginaResponse<ProdutoResponse> listarProdutos(Long id, Integer pagina, Integer tamanho) {
        if (!eventoRepository.existsById(id)) {
            throw new EventoNaoEncontradoException();
        }
        Pageable pageable = PaginacaoParams.paraPageable(pagina, tamanho, ORDENACAO_VITRINE);
        Page<Produto> page = produtoRepository.buscarPorEvento(id, pageable);
        // A variante de vitrine (sazonal=true fixo, eventoIds=null, sem bytea) mora no proprio
        // ProdutoResponse desde o M6 — reconstruir o record posicionalmente aqui quebrava a cada
        // componente novo do contrato de produto (§12 #4).
        return PaginaResponse.de(page, ProdutoResponse::deVitrine);
    }

    /** Detalha um evento por id (CA-5). Inexistente → {@link EventoNaoEncontradoException} (404). */
    @Transactional(readOnly = true)
    public EventoResponse detalhar(Long id) {
        return eventoRepository.findById(id)
                .map(EventoResponse::de)
                .orElseThrow(EventoNaoEncontradoException::new);
    }

    /**
     * Cria um evento (CA-1/CA-2/CA-3). Valida a regra cruzada de datas ({@code dataFim >= dataInicio}) →
     * {@link DataEventoInvalidaException} (400). {@code repeteTodoAno} ausente = {@code false}.
     *
     * <p><b>Sem {@code @Transactional} aqui de proposito</b> (mesmo motivo de {@code ProdutoService.
     * criar}): com {@code open-in-view=false}, o {@code save} e a releitura correm em contextos
     * distintos, e so a releitura traz {@code criado_em}/{@code atualizado_em} (colunas
     * {@code insertable=false}, do {@code DEFAULT now()} do banco).
     */
    public EventoResponse criar(EventoRequest req) {
        validarDatas(req.dataInicio(), req.dataFim());
        Evento evento = EventoFactory.novo(
                req.nome(),
                req.tipo(),
                req.dataInicio(),
                req.dataFim(),
                Boolean.TRUE.equals(req.repeteTodoAno()),
                req.descricao());
        Long id = eventoRepository.save(evento).getId();
        return eventoRepository.findById(id)
                .map(EventoResponse::de)
                .orElseThrow(EventoNaoEncontradoException::new);
    }

    /**
     * Atualiza um evento (CA-6). Inexistente → {@link EventoNaoEncontradoException} (404). Valida a
     * regra cruzada de datas (400) e estampa {@code atualizado_em = now()} (§4.1).
     */
    @Transactional
    public EventoResponse atualizar(Long id, EventoRequest req) {
        validarDatas(req.dataInicio(), req.dataFim());
        Evento evento = eventoRepository.findById(id)
                .orElseThrow(EventoNaoEncontradoException::new);
        evento.setNome(req.nome());
        evento.setTipo(req.tipo());
        evento.setDataInicio(req.dataInicio());
        evento.setDataFim(req.dataFim());
        evento.setRepeteTodoAno(Boolean.TRUE.equals(req.repeteTodoAno()));
        evento.setDescricao(req.descricao());
        evento.setAtualizadoEm(Instant.now());
        return EventoResponse.de(eventoRepository.save(evento));
    }

    /**
     * Hard delete de evento (CA-7, FC-08 — IRREVERSIVEL). Inexistente →
     * {@link EventoNaoEncontradoException} (404). O {@code ON DELETE CASCADE} da V6 remove as linhas de
     * {@code evento_produto} desse evento no banco; os produtos permanecem. A confirmacao e do front.
     */
    @Transactional
    public void deletar(Long id) {
        if (!eventoRepository.existsById(id)) {
            throw new EventoNaoEncontradoException();
        }
        eventoRepository.deleteById(id);
    }

    /** Regra cruzada (CA-2): se {@code dataFim} presente e menor que {@code dataInicio} → 400. */
    private void validarDatas(LocalDate dataInicio, LocalDate dataFim) {
        if (dataFim != null && dataInicio != null && dataFim.isBefore(dataInicio)) {
            throw new DataEventoInvalidaException();
        }
    }
}
