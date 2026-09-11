package com.floricultura.api.web.dto;

import com.floricultura.api.web.response.FieldErrorItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parametros de filtro/ordenacao da lista de produtos — {@code GET /produtos} (SPEC-M6 §3.6,
 * CA-19..CA-23). Carrega os params <b>ja validados e normalizados</b>; quem traduz para SQL e a
 * {@code ProdutoSpecs}.
 *
 * <p><b>Semantica (P4):</b> dimensoes diferentes combinam com <b>E</b>; dentro da mesma dimensao
 * multivalorada ({@code caracteristica}, {@code toxicidade}) vale <b>OU</b> ({@code IN}). Lista vazia =
 * dimensao sem filtro. Os atalhos de estoque (P10) <b>nao</b> particionam: {@code SEM_ESTOQUE} ⊂
 * {@code BAIXO}, porque {@code BAIXO} repete a regra do selo {@code estoqueBaixo} (FC-13).
 *
 * <p>A validacao roda toda na fabrica {@link #de} e <b>acumula</b> um {@link FieldErrorItem} por campo
 * antes de lancar {@link ParametroPaginacaoInvalidoException} (→ {@code 400 VALIDATION_ERROR} no
 * handler local do {@code ProdutoController}, com o {@code field} certo). Ausente/em branco assume o
 * default ({@code nome} / {@code asc}) ou "sem filtro".
 *
 * @param nome           termo do {@code ILIKE '%nome%'} ja aparado; {@code null} = sem filtro
 * @param estoque        atalho {@code SEM_ESTOQUE|BAIXO|COM_ESTOQUE}; {@code null} = sem filtro
 * @param precoMin       piso <b>inclusivo</b> do preco; {@code null} = sem piso
 * @param precoMax       teto <b>inclusivo</b> do preco; {@code null} = sem teto
 * @param semPreco       {@code true} = so produtos com {@code preco IS NULL} (exclui faixa)
 * @param caracteristica porte(s) aceito(s) ({@code MUDA|JOVEM|ADULTA}), OU interno; vazio = sem filtro
 * @param toxicidade     toxicidade(s) aceita(s) ({@code TOXICA|NAO_TOXICA}), OU interno
 * @param ordenarPor     campo ja normalizado ({@code nome}/{@code estoque}/{@code preco})
 * @param direcao        sentido ja normalizado ({@code asc}/{@code desc})
 */
public record ProdutoFiltro(
        String nome, String estoque, BigDecimal precoMin, BigDecimal precoMax, boolean semPreco,
        List<String> caracteristica, List<String> toxicidade, String ordenarPor, String direcao) {

    /** Campo de ordenacao default (§3.6) — preserva a ordem {@code nome ASC} do M2. */
    public static final String ORDENAR_POR_PADRAO = "nome";

    /** Sentido de ordenacao default (§3.6). */
    public static final String DIRECAO_PADRAO = "asc";

    private static final Set<String> ESTOQUES = Set.of("SEM_ESTOQUE", "BAIXO", "COM_ESTOQUE");
    private static final Set<String> CARACTERISTICAS = Set.of("MUDA", "JOVEM", "ADULTA");
    private static final Set<String> TOXICIDADES = Set.of("TOXICA", "NAO_TOXICA");
    private static final Set<String> CAMPOS = Set.of("nome", "estoque", "preco");
    private static final Set<String> DIRECOES = Set.of("asc", "desc");

    /**
     * Valida e normaliza os parametros crus da query string (§3.6). Enum fora do conjunto, preco
     * negativo, {@code precoMin > precoMax} e {@code semPreco} combinado com a faixa acumulam erro por
     * campo e viram {@code 400} (CA-19/CA-20/CA-21).
     */
    public static ProdutoFiltro de(String nome, String estoque, BigDecimal precoMin,
            BigDecimal precoMax, Boolean semPreco, List<String> caracteristica,
            List<String> toxicidade, String ordenarPor, String direcao) {
        List<FieldErrorItem> erros = new ArrayList<>();

        String atalho = (estoque == null || estoque.isBlank()) ? null : estoque.trim();
        if (atalho != null && !ESTOQUES.contains(atalho)) {
            erros.add(new FieldErrorItem(
                    "estoque", "Deve ser um de: SEM_ESTOQUE, BAIXO, COM_ESTOQUE."));
        }
        List<String> portes = multivalorado(caracteristica, CARACTERISTICAS, "caracteristica",
                "Deve ser um de: MUDA, JOVEM, ADULTA.", erros);
        List<String> toxinas = multivalorado(toxicidade, TOXICIDADES, "toxicidade",
                "Deve ser um de: TOXICA, NAO_TOXICA.", erros);
        boolean sem = Boolean.TRUE.equals(semPreco);
        validarPreco(precoMin, precoMax, sem, erros);

        String campo = normalizar(ordenarPor, ORDENAR_POR_PADRAO);
        String sentido = normalizar(direcao, DIRECAO_PADRAO);
        if (!CAMPOS.contains(campo)) {
            erros.add(new FieldErrorItem("ordenarPor", "Deve ser um de: nome, estoque, preco."));
        }
        if (!DIRECOES.contains(sentido)) {
            erros.add(new FieldErrorItem("direcao", "Deve ser asc ou desc."));
        }
        if (!erros.isEmpty()) {
            throw new ParametroPaginacaoInvalidoException("Parametros de listagem invalidos.", erros);
        }

        String termo = (nome == null || nome.isBlank()) ? null : nome.trim();
        return new ProdutoFiltro(
                termo, atalho, precoMin, precoMax, sem, portes, toxinas, campo, sentido);
    }

    /** {@code true} quando a ordenacao e ascendente (default). */
    public boolean ascendente() {
        return DIRECAO_PADRAO.equals(direcao);
    }

    // Faixa de preco (§3.6): limites INCLUSIVOS, nunca negativos, coerentes entre si e mutuamente
    // exclusivos com semPreco=true — pedir "sem preco" E faixa devolveria sempre vazio, entao e erro
    // de contrato (400 field:"semPreco"), nao lista vazia.
    private static void validarPreco(
            BigDecimal min, BigDecimal max, boolean semPreco, List<FieldErrorItem> erros) {
        if (min != null && min.signum() < 0) {
            erros.add(new FieldErrorItem("precoMin", "Deve ser maior ou igual a 0."));
        }
        if (max != null && max.signum() < 0) {
            erros.add(new FieldErrorItem("precoMax", "Deve ser maior ou igual a 0."));
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            erros.add(new FieldErrorItem("precoMin", "Deve ser menor ou igual a precoMax."));
        }
        if (semPreco && (min != null || max != null)) {
            erros.add(new FieldErrorItem(
                    "semPreco", "Nao pode ser combinado com precoMin/precoMax."));
        }
    }

    // Dimensao multivalorada (§3.6): nulos/brancos descartados, valores aparados e deduplicados;
    // valor fora do enum acumula UM erro no campo (nunca um por item repetido).
    private static List<String> multivalorado(List<String> valores, Set<String> aceitos,
            String campo, String mensagem, List<FieldErrorItem> erros) {
        if (valores == null) {
            return List.of();
        }
        List<String> dedup = valores.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim).distinct().toList();
        if (!aceitos.containsAll(dedup)) {
            erros.add(new FieldErrorItem(campo, mensagem));
        }
        return dedup;
    }

    private static String normalizar(String valor, String padrao) {
        return (valor == null || valor.isBlank()) ? padrao : valor.trim().toLowerCase(Locale.ROOT);
    }
}
