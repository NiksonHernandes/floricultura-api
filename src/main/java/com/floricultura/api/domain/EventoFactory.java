package com.floricultura.api.domain;

import java.time.LocalDate;

/**
 * Fabrica de {@link Evento} para a criacao por ADMIN (SPEC-M4 §3.2/§4.1, CA-1). Vive no MESMO pacote de
 * {@link Evento} porque o construtor da entidade e {@code protected} (exigido pelo JPA) — este helper
 * permite instancia-la a partir da camada de servico <b>sem alterar a entity</b> (mesma convencao de
 * {@code ProdutoFactory}/{@code MovimentacaoFactory}).
 *
 * <p>{@code criado_em}/{@code atualizado_em} <b>nao</b> sao setados aqui: vem do {@code DEFAULT now()}
 * do banco (colunas {@code insertable=false}). A validacao cruzada de datas (dataFim &gt;= dataInicio) e
 * responsabilidade do {@code EventoService} (400 antes do save).
 */
public final class EventoFactory {

    private EventoFactory() {
        // Utilitaria — sem instancia.
    }

    /**
     * Cria um evento transiente pronto para {@code save}. Os campos ja chegam validados na forma pelo
     * {@code EventoRequest} (Bean Validation) e na regra cruzada de datas pelo servico.
     *
     * @param nome          nome de exibicao (obrigatorio)
     * @param tipo          codigo do tipo em ASCII (∈ enum da V6)
     * @param dataInicio    inicio do evento (obrigatorio)
     * @param dataFim       fim do periodo (pode ser {@code null} ⇒ data unica)
     * @param repeteTodoAno recorrencia anual
     * @param descricao     descricao livre (pode ser {@code null})
     * @return entidade transiente pronta para persistir
     */
    public static Evento novo(
            String nome,
            String tipo,
            LocalDate dataInicio,
            LocalDate dataFim,
            boolean repeteTodoAno,
            String descricao) {
        Evento evento = new Evento();
        evento.setNome(nome);
        evento.setTipo(tipo);
        evento.setDataInicio(dataInicio);
        evento.setDataFim(dataFim);
        evento.setRepeteTodoAno(repeteTodoAno);
        evento.setDescricao(descricao);
        return evento;
    }
}
