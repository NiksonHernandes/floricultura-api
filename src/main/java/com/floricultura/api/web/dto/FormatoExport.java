package com.floricultura.api.web.dto;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Formato do arquivo em {@code GET /api/v1/relatorios/movimentacoes/export} (SPEC-M7 §3.8, T-M7-06) —
 * espelho do {@link Granularidade} para o parametro {@code formato}: conjunto <b>fechado</b>, com o
 * {@code Content-Type} e a extensao do nome do arquivo cravados no contrato morando <b>aqui</b>, e nao
 * espalhados pelo controller.
 *
 * <p><b>Ausente e fora-do-conjunto caem no MESMO 400</b> (§3.8: "{@code formato} ausente ou fora do
 * conjunto ⇒ 400 {@code field=formato}"), com a <b>gramatica unica</b> da AD-SQ-168(2)
 * — {@code <param> deve ser um de: A, B} —, a mesma frase que {@code tipo} e {@code granularidade} ja
 * usam. A lista da mensagem e <b>derivada de {@link #values()}</b>: um formato novo entra na frase
 * sozinho, sem texto duplicado para esquecer de atualizar.
 *
 * <p><b>O parametro continua {@code required = false} no controller</b> (AD-SQ-162): o
 * {@code GlobalExceptionHandler} nao trata {@code MissingServletRequestParameterException}, entao
 * declara-lo obrigatorio no Spring devolveria <b>500</b> para um erro de cliente. A obrigatoriedade e
 * validada por {@link #fromWire(String)}.
 */
public enum FormatoExport {

    XLSX(FormatoExport.CONTENT_TYPE_XLSX, "xlsx");

    /**
     * {@code Content-Type} do XLSX (§3.8). Constante de compilacao porque o {@code produces} do
     * {@code @GetMapping} so aceita literal — o enum nao serve em anotacao.
     */
    public static final String CONTENT_TYPE_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Mesma mensagem-topo dos demais 400 de parametro de listagem/relatorio (§3.5/§3.7). */
    private static final String MSG = "Parametros de filtro invalidos.";

    private final String contentType;
    private final String extensao;

    FormatoExport(String contentType, String extensao) {
        this.contentType = contentType;
        this.extensao = extensao;
    }

    /** {@code Content-Type} da resposta binaria (fora do envelope, precedente AD-SQ-37). */
    public String contentType() {
        return contentType;
    }

    /** Extensao do nome {@code movimentacoes-<de>_a_<ate>.<ext>} (§3.8). */
    public String extensao() {
        return extensao;
    }

    /**
     * Ausente, em branco ou fora do conjunto ⇒ 400 {@code field=formato}. Comparacao <b>sensivel a
     * caixa</b>, como {@code granularidade} e {@code tipo} — um so dialeto de conjunto fechado na API.
     */
    public static FormatoExport fromWire(String valor) {
        if (valor != null && !valor.isBlank()) {
            try {
                return valueOf(valor.trim());
            } catch (IllegalArgumentException foraDoConjunto) {
                throw invalido();
            }
        }
        throw invalido();
    }

    private static ParametroPaginacaoInvalidoException invalido() {
        String aceitos = Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
        return new ParametroPaginacaoInvalidoException(
                MSG, List.of(new FieldErrorItem("formato", "formato deve ser um de: " + aceitos)));
    }
}
