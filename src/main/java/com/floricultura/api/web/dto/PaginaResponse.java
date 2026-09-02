package com.floricultura.api.web.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Envelope de listagem paginada reutilizavel (SPEC-M2 §3.3 / AD-SQ-29): {@code data} de todo
 * {@code GET} de lista ({@code /produtos}, {@code /produtos/{id}/movimentacoes}, {@code /usuarios}).
 *
 * <p><b>Nao serializar {@code Page}/{@code PageImpl} do Spring diretamente</b> (formato instavel
 * entre versoes — §12): o servico usa {@code Pageable}/{@code Page} internamente e mapeia para este
 * record proprio, cujo formato e o contrato estavel {@code (conteudo, pagina, tamanho,
 * totalElementos, totalPaginas, primeira, ultima)}.
 *
 * @param conteudo       itens da pagina atual (ja mapeados para o DTO de resposta)
 * @param pagina         indice 0-based da pagina atual (AD-SQ-29)
 * @param tamanho        tamanho da pagina solicitado
 * @param totalElementos total de elementos que satisfazem o filtro (todas as paginas)
 * @param totalPaginas   total de paginas
 * @param primeira       {@code true} se esta e a primeira pagina
 * @param ultima         {@code true} se esta e a ultima pagina (pagina alem do total → vazia+ultima)
 */
public record PaginaResponse<T>(
        List<T> conteudo,
        int pagina,
        int tamanho,
        long totalElementos,
        int totalPaginas,
        boolean primeira,
        boolean ultima) {

    /**
     * Mapeia um {@link Page} do Spring Data para o contrato estavel §3.3, convertendo cada elemento
     * da entidade para o DTO de resposta via {@code mapper}. Uma pagina alem do total volta com
     * {@code conteudo} vazio e {@code ultima=true} (nao e erro — §4).
     */
    public static <E, T> PaginaResponse<T> de(Page<E> page, Function<E, T> mapper) {
        return new PaginaResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
