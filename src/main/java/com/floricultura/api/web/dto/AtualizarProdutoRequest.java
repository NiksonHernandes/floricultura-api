package com.floricultura.api.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Corpo de {@code PUT /api/v1/produtos/{id}} (SPEC-M2 §3.2, CA-7/CA-9) — edicao de produto pelo ADMIN.
 * Mesmos campos e regras do {@link CriarProdutoRequest} (§3.2), mantidos como <b>records separados</b>
 * por serem contratos nomeados distintos (ratificado no gate T-M2-3).
 *
 * <p><b>{@code estoqueAtual} NAO aparece aqui</b> (AD-SQ-30): o {@code PUT} altera so campos de
 * cadastro e <b>nunca</b> toca o estoque — a unica via de alteracao e a movimentacao (T-M2-4).
 *
 * @param nome          nome de exibicao (obrigatorio, 2..150)
 * @param descricao     descricao livre (opcional)
 * @param unidadeMedida codigo da unidade (obrigatorio, ∈ {@code un,kg,saco,m3,l,g} — AD-SQ-31)
 * @param estoqueMinimo estoque minimo (obrigatorio, {@code >= 0})
 * @param preco         preco unitario (opcional; se presente {@code >= 0} — AD-SQ-28)
 * @param imagemUrl     URL da imagem (opcional, ≤1000; sem validacao de existencia — AD-SQ-32)
 * @param eventoIds     ids dos eventos a vincular (opcional; default {@code []} — M4/§3.4, CA-9/CA-11).
 *                      Replace-set no servico; id inexistente → 400 {@code field=eventoIds}
 * @param caracteristica porte ∈ {@code {MUDA,JOVEM,ADULTA}} (SPEC-M6 §3.3). <b>Escalar</b>: o PUT grava
 *                      o que veio, <b>inclusive {@code null}</b> (limpa) — mesma semantica de
 *                      {@code descricao}/{@code preco} do M2, e nao a de {@code eventoIds}
 * @param alturaCm      altura em centimetros inteiros 1..10000 (R12); escalar (null limpa). A regra
 *                      cruzada R13 e avaliada sobre o <b>estado RESULTANTE</b> do update — como os 3
 *                      escalares sao substituidos integralmente, o resultante e o proprio par
 *                      ({@code caracteristica}, {@code alturaCm}) do payload
 * @param toxicidade    {@code TOXICA|NAO_TOXICA}; escalar, {@code null} = nao informado (R8/P2)
 */
public record AtualizarProdutoRequest(
        @NotBlank @Size(min = 2, max = 150) String nome,
        String descricao,
        @NotBlank @Pattern(regexp = "un|kg|saco|m3|l|g",
                message = "unidadeMedida deve ser um de: un, kg, saco, m3, l, g") String unidadeMedida,
        @NotNull @DecimalMin(value = "0", message = "estoqueMinimo deve ser >= 0")
                BigDecimal estoqueMinimo,
        @DecimalMin(value = "0", message = "preco deve ser >= 0") BigDecimal preco,
        @Size(max = 1000) String imagemUrl,
        List<Long> eventoIds,
        @Pattern(regexp = "MUDA|JOVEM|ADULTA",
                message = "caracteristica deve ser um de: MUDA, JOVEM, ADULTA") String caracteristica,
        @Min(value = 1, message = "A altura deve estar entre 1 e 10000 cm.")
                @Max(value = 10000, message = "A altura deve estar entre 1 e 10000 cm.")
                Integer alturaCm,
        @Pattern(regexp = "TOXICA|NAO_TOXICA",
                message = "toxicidade deve ser um de: TOXICA, NAO_TOXICA") String toxicidade) {

    /**
     * Construtor de compatibilidade (M2): 6 campos, sem {@code eventoIds} ⇒ {@code null} = "vinculos
     * nao informados" (o servico nao mexe nos links — update parcial; M4 sempre envia o campo).
     * Preserva chamadas positionais do M2 sem tocar testes existentes (aditivo).
     */
    public AtualizarProdutoRequest(String nome, String descricao, String unidadeMedida,
            BigDecimal estoqueMinimo, BigDecimal preco, String imagemUrl) {
        this(nome, descricao, unidadeMedida, estoqueMinimo, preco, imagemUrl, null);
    }

    /**
     * Construtor de compatibilidade (M4): 7 campos, sem os atributos botanicos do M6 ⇒ os 3 escalares
     * chegam {@code null} (e o PUT, sendo escalar, os LIMPA — §3.3). Os componentes novos entram ao FIM
     * do record justamente para que estes dois ctors posicionais sigam validos (§3.4/§12 #4).
     */
    public AtualizarProdutoRequest(String nome, String descricao, String unidadeMedida,
            BigDecimal estoqueMinimo, BigDecimal preco, String imagemUrl, List<Long> eventoIds) {
        this(nome, descricao, unidadeMedida, estoqueMinimo, preco, imagemUrl, eventoIds,
                null, null, null);
    }
}
