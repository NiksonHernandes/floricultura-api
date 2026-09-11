package com.floricultura.api.web.dto;

import com.floricultura.api.web.response.FieldErrorItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parametros de filtro/ordenacao das listas de contato — {@code GET /clientes} e
 * {@code GET /fornecedores} (SPEC-M6 §3.7, CA-24/CA-25). Contrato <b>identico</b> nos dois cadastros
 * (D3: nenhum campo novo no schema — o filtro usa {@code telefone}/{@code email} que ja existem).
 *
 * <p><b>Tri-estado</b> (armadilha 14 do §12): {@code comTelefone}/{@code comEmail} sao {@link Boolean}
 * (objeto), nunca {@code boolean} — {@code null} = sem filtro, {@code TRUE} = tem, {@code FALSE} =
 * <b>nao</b> tem (nulo <b>ou</b> vazio apos {@code trim}, R25). Os dois combinam com <b>E</b> entre si
 * e com {@code nome}.
 *
 * <p>A validacao roda na fabrica {@link #de}: {@code ordenarPor} fora de {@code nome|telefone|email} ou
 * {@code direcao} fora de {@code asc|desc} → {@link ParametroPaginacaoInvalidoException} com um
 * {@link FieldErrorItem} por campo (→ {@code 400 VALIDATION_ERROR} no handler local ja existente dos
 * controllers). Ausente/em branco assume o default ({@code nome} / {@code asc}).
 *
 * @param nome        termo do {@code ILIKE '%nome%'} ja aparado; {@code null} = sem filtro
 * @param comTelefone tri-estado do filtro de telefone
 * @param comEmail    tri-estado do filtro de e-mail
 * @param ordenarPor  campo de ordenacao ja normalizado ({@code nome}/{@code telefone}/{@code email})
 * @param direcao     sentido ja normalizado ({@code asc}/{@code desc})
 */
public record ContatoFiltro(
        String nome, Boolean comTelefone, Boolean comEmail, String ordenarPor, String direcao) {

    /** Campo de ordenacao default (§3.7). */
    public static final String ORDENAR_POR_PADRAO = "nome";

    /** Sentido de ordenacao default (§3.7). */
    public static final String DIRECAO_PADRAO = "asc";

    private static final Set<String> CAMPOS = Set.of("nome", "telefone", "email");

    private static final Set<String> DIRECOES = Set.of("asc", "desc");

    /**
     * Valida e normaliza os parametros crus da query string. {@code nome} em branco vira {@code null}
     * (sem filtro); {@code ordenarPor}/{@code direcao} sao aparados e minusculados antes da checagem.
     * Valor fora do conjunto acumula um {@link FieldErrorItem} por campo e lanca
     * {@link ParametroPaginacaoInvalidoException} (CA-25).
     */
    public static ContatoFiltro de(
            String nome, Boolean comTelefone, Boolean comEmail, String ordenarPor, String direcao) {
        String campo = normalizar(ordenarPor, ORDENAR_POR_PADRAO);
        String sentido = normalizar(direcao, DIRECAO_PADRAO);

        List<FieldErrorItem> erros = new ArrayList<>();
        if (!CAMPOS.contains(campo)) {
            erros.add(new FieldErrorItem("ordenarPor", "Deve ser um de: nome, telefone, email."));
        }
        if (!DIRECOES.contains(sentido)) {
            erros.add(new FieldErrorItem("direcao", "Deve ser asc ou desc."));
        }
        if (!erros.isEmpty()) {
            throw new ParametroPaginacaoInvalidoException(
                    "Parametros de listagem invalidos.", erros);
        }

        String termo = (nome == null || nome.isBlank()) ? null : nome.trim();
        return new ContatoFiltro(termo, comTelefone, comEmail, campo, sentido);
    }

    /** {@code true} quando a ordenacao e ascendente (default). */
    public boolean ascendente() {
        return DIRECAO_PADRAO.equals(direcao);
    }

    private static String normalizar(String valor, String padrao) {
        return (valor == null || valor.isBlank())
                ? padrao
                : valor.trim().toLowerCase(Locale.ROOT);
    }
}
