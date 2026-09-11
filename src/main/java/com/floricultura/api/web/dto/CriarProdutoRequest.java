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
 * Corpo de {@code POST /api/v1/produtos} (SPEC-M2 §3.2, CA-6/CA-9) — criacao de produto pelo ADMIN.
 *
 * <p>Regras de validacao (Bean Validation → {@code 400 VALIDATION_ERROR} com {@code details} pelo
 * {@code GlobalExceptionHandler} do M0): {@code nome} obrigatorio 2..150; {@code unidadeMedida}
 * obrigatoria e ∈ {@code {un,kg,saco,m3,l,g}} (AD-SQ-31 — codigo ASCII, {@code m³} so na exibicao do
 * front); {@code estoqueMinimo} obrigatorio {@code >= 0}; {@code preco} <b>opcional</b>, se presente
 * {@code >= 0} (AD-SQ-28); {@code descricao} opcional; {@code imagemUrl} opcional ≤1000, sem validacao
 * de existencia (AD-SQ-32). O CHECK do enum/preco vive tambem na V3 (defesa em profundidade).
 *
 * <p><b>{@code estoqueAtual} NAO aparece aqui</b> (AD-SQ-30): produto nasce com {@code estoqueAtual=0}
 * e estoque so muda via movimentacao (T-M2-4) — o CRUD nunca o toca.
 *
 * @param nome          nome de exibicao (obrigatorio, 2..150)
 * @param descricao     descricao livre (opcional)
 * @param unidadeMedida codigo da unidade (obrigatorio, ∈ {@code un,kg,saco,m3,l,g})
 * @param estoqueMinimo estoque minimo (obrigatorio, {@code >= 0})
 * @param preco         preco unitario (opcional; se presente {@code >= 0})
 * @param imagemUrl     URL da imagem (opcional, ≤1000; sem validacao de existencia)
 * @param eventoIds     ids dos eventos a vincular (opcional; default {@code []} — M4/§3.4, CA-9/CA-11).
 *                      Replace-set no servico; id inexistente → 400 {@code field=eventoIds}
 * @param caracteristica porte da planta ∈ {@code {MUDA,JOVEM,ADULTA}}, opcional (SPEC-M6 §3.3/CA-8);
 *                      escalar — valor grava, {@code null} limpa
 * @param alturaCm      altura em <b>centimetros inteiros</b> 1..10000 (R12/P1 — a conversao m↔cm e do
 *                      front), opcional; so aceita com {@code caracteristica ∈ {JOVEM,ADULTA}} (R13,
 *                      validada no servico ANTES do banco → 400 {@code field=alturaCm}; sem essa
 *                      validacao o CHECK da V12 devolveria 409 sem {@code field} — AD-SQ-119)
 * @param toxicidade    {@code TOXICA|NAO_TOXICA}, opcional; ausente = <b>nao informado</b> (tri-estado
 *                      por ausencia — R8/P2; nao existe {@code NAO_INFORMADO} no contrato)
 * @param necessidadeLuz valores em {@code {SOL_PLENO,MEIA_SOMBRA,SOMBRA}} — <b>replace-set</b> com
 *                      semantica IDENTICA a de {@code eventoIds} (R9/AD-SQ-44): {@code null} = nao
 *                      altera · {@code []} = limpa · repetidos deduplicados. Fora do conjunto → 400
 *                      {@code field=necessidadeLuz} (validado no servico — §3.3)
 * @param corIds        ids de {@code cor} — replace-set, mesma semantica. Id inexistente → 400
 *                      {@code field=corIds} <b>antes de qualquer escrita</b> (R10/CA-11), em UMA query
 */
public record CriarProdutoRequest(
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
                message = "toxicidade deve ser um de: TOXICA, NAO_TOXICA") String toxicidade,
        List<String> necessidadeLuz,
        List<Long> corIds) {

    /**
     * Construtor de compatibilidade (M2): 6 campos, sem {@code eventoIds} ⇒ {@code null} = "vinculos
     * nao informados" (o servico nao mexe nos links — update parcial; M4 sempre envia o campo).
     * Preserva chamadas positionais do M2 sem tocar testes existentes (aditivo).
     */
    public CriarProdutoRequest(String nome, String descricao, String unidadeMedida,
            BigDecimal estoqueMinimo, BigDecimal preco, String imagemUrl) {
        this(nome, descricao, unidadeMedida, estoqueMinimo, preco, imagemUrl, null);
    }

    /**
     * Construtor de compatibilidade (M4): 7 campos, sem os atributos botanicos do M6 ⇒ os 3 escalares
     * nascem {@code null} (P12 — produto sem atributo declarado) e os 2 multivalorados tambem
     * ({@code null} = "nao informados" ⇒ o servico nao mexe nos conjuntos — R9). Os componentes novos
     * entram ao FIM do record para que estes ctors posicionais sigam validos (§12 #4).
     */
    public CriarProdutoRequest(String nome, String descricao, String unidadeMedida,
            BigDecimal estoqueMinimo, BigDecimal preco, String imagemUrl, List<Long> eventoIds) {
        this(nome, descricao, unidadeMedida, estoqueMinimo, preco, imagemUrl, eventoIds,
                null, null, null, null, null);
    }

    /**
     * Compat (T-M6-02a): 10 campos ⇒ os 2 multivalorados ficam {@code null} = "nao informados" (R9).
     * Mesma politica: ampliar o record nao pode quebrar chamada posicional existente (§3.4/§12 #4).
     */
    public CriarProdutoRequest(String nome, String descricao, String unidadeMedida,
            BigDecimal estoqueMinimo, BigDecimal preco, String imagemUrl, List<Long> eventoIds,
            String caracteristica, Integer alturaCm, String toxicidade) {
        this(nome, descricao, unidadeMedida, estoqueMinimo, preco, imagemUrl, eventoIds,
                caracteristica, alturaCm, toxicidade, null, null);
    }
}
