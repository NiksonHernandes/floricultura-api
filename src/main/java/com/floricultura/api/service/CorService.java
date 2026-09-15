package com.floricultura.api.service;

import com.floricultura.api.domain.Cor;
import com.floricultura.api.domain.CorComUso;
import com.floricultura.api.domain.CorFactory;
import com.floricultura.api.domain.Cores;
import com.floricultura.api.repository.CorRepository;
import com.floricultura.api.web.dto.PaginacaoParams;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de negocio do catalogo de cores (SPEC-M6 §3.2/§3.2.1) — CRUD + canonizacao + contagem de uso.
 * Devolve {@link CorComUso} (dominio): o mapeamento para o DTO de web e do {@code CorController}
 * (T-M6-01b-2), assim como o RBAC (matchers do {@code SecurityConfig}).
 *
 * <p>{@link CorRepository} injetado como {@link Lazy} (padrao {@code FornecedorService}): os smokes do M0
 * sobem sem JPA.
 */
@Service
public class CorService {

    private final CorRepository corRepository;

    public CorService(@Lazy CorRepository corRepository) {
        this.corRepository = corRepository;
    }

    /**
     * Lista paginada, ordenada por {@code nome ASC} (CA-4), com filtro opcional por substring. O termo
     * passa pelo MESMO {@link Cores#canonizar(String)} da escrita (CA-39.6 — "cinza escuro" acha
     * {@code CINZA-ESCURO}); termo que canoniza para vazio = <b>sem filtro</b> (leitura tolerante, PR3).
     */
    @Transactional(readOnly = true)
    public Page<CorComUso> listar(Integer pagina, Integer tamanho, String nome) {
        Pageable pageable = PaginacaoParams.paraPageable(pagina, tamanho, Sort.by("nome").ascending());
        String termo = Cores.canonizar(nome);
        Page<Cor> page = termo.isEmpty()
                ? corRepository.findAll(pageable)
                : corRepository.findByNomeContaining(termo, pageable);
        return comUso(page);
    }

    /** Detalhe por id; inexistente → {@link CorNaoEncontradaException} (404). */
    @Transactional(readOnly = true)
    public CorComUso detalhar(Long id) {
        Cor cor = buscar(id);
        return new CorComUso(cor, corRepository.contarProdutos(id));
    }

    /**
     * Cria uma cor (CA-1): persiste o CANONICO e o hex em maiusculas. Canonico ja existente → 409
     * (CA-2). <b>Sem {@code @Transactional} de proposito</b> (mesmo motivo de
     * {@code FornecedorService.criar}): so a releitura traz {@code criado_em}/{@code atualizado_em},
     * colunas {@code insertable=false} vindas do {@code DEFAULT now()} do banco.
     */
    public CorComUso criar(String nome, String hex) {
        String canonico = canonizarOuFalhar(nome);
        if (corRepository.existsByNome(canonico)) {
            throw CorConflitoException.duplicada();
        }
        Cor salvo = corRepository.save(CorFactory.novo(canonico, Cores.normalizarHex(hex)));
        // Cor recem-criada nao tem vinculo: 0 sem ir ao banco (o produto_cor so e escrito pelo produto).
        return new CorComUso(buscar(salvo.getId()), 0L);
    }

    /**
     * Atualiza nome/hex (CA-5). Inexistente → 404 (checado primeiro); canonico de OUTRA cor → 409;
     * renomear para o proprio canonico (ate com caixa diferente) → 200. Estampa {@code atualizado_em}.
     */
    @Transactional
    public CorComUso atualizar(Long id, String nome, String hex) {
        Cor cor = buscar(id);
        String canonico = canonizarOuFalhar(nome);
        if (corRepository.existsByNomeAndIdNot(canonico, id)) {
            throw CorConflitoException.duplicada();
        }
        cor.setNome(canonico);
        cor.setHex(Cores.normalizarHex(hex));
        cor.setAtualizadoEm(Instant.now());
        corRepository.save(cor);
        return new CorComUso(cor, corRepository.contarProdutos(id));
    }

    /**
     * Hard delete (CA-6). Inexistente → 404. Cor vinculada a produto → 409 com a contagem REAL, contada
     * <b>antes</b> de tentar deletar (o {@code ON DELETE RESTRICT} nunca deveria ser atingido).
     */
    @Transactional
    public void excluir(Long id) {
        Cor cor = buscar(id);
        long vinculados = corRepository.contarProdutos(id);
        if (vinculados > 0) {
            throw CorConflitoException.emUso(vinculados);
        }
        corRepository.delete(cor);
    }

    // ---- Internos -----------------------------------------------------------------------------

    private Cor buscar(Long id) {
        return corRepository.findById(id).orElseThrow(CorNaoEncontradaException::new);
    }

    /** Canoniza e aplica a validacao autoritativa {@code 2..40} SOBRE O CANONICO (§3.2.1/CA-39.4/5). */
    private String canonizarOuFalhar(String nome) {
        String canonico = Cores.canonizar(nome);
        if (canonico.isEmpty()) {
            throw new NomeCorInvalidoException("Informe um nome de cor válido.");
        }
        if (canonico.length() < Cores.NOME_MIN) {
            throw new NomeCorInvalidoException("O nome da cor deve ter ao menos 2 caracteres.");
        }
        if (canonico.length() > Cores.NOME_MAX) {
            throw new NomeCorInvalidoException("O nome da cor deve ter no máximo 40 caracteres.");
        }
        return canonico;
    }

    /** Casa a pagina com a contagem agrupada: 1 query de pagina + 1 {@code GROUP BY} (CA-4, sem N+1). */
    private Page<CorComUso> comUso(Page<Cor> page) {
        List<Long> ids = page.getContent().stream().map(Cor::getId).toList();
        Map<Long, Long> uso = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] linha : corRepository.contarProdutosPorCor(ids)) {
                uso.put(((Number) linha[0]).longValue(), ((Number) linha[1]).longValue());
            }
        }
        return page.map(cor -> new CorComUso(cor, uso.getOrDefault(cor.getId(), 0L)));
    }
}
