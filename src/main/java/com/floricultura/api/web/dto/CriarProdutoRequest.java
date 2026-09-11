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
 *                      validada no servico ANTES do banco → 400 {@code field=alturaCm}, nunca 500)
 * @param toxicidade    {@code TOXICA|NAO_TOXICA}, opcional; ausente = <b>nao informado</b> (tri-estado
 *                      por ausencia — R8/P2; nao existe {@code NAO_INFORMADO} no contrato)
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
                message = "toxicidade deve ser um de: TOXICA, NAO_TOXICA") String toxicidade) {

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
     * nascem {@code null} (P12 — produto sem atributo declarado). Os componentes novos entram ao FIM do
     * record justamente para que estes dois ctors posicionais sigam validos (§3.4/§12 #4).
     */
    public CriarProdutoRequest(String nome, String descricao, String unidadeMedida,
            BigDecimal estoqueMinimo, BigDecimal preco, String imagemUrl, List<Long> eventoIds) {
        this(nome, descricao, unidadeMedida, estoqueMinimo, preco, imagemUrl, eventoIds,
                null, null, null);
    }
}
