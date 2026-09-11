package com.floricultura.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Formula;

/**
 * Espelho fiel (SPEC-M2 §8) da tabela {@code produto} — schema V1 (SPEC-M0 §3.6) + coluna
 * {@code preco} da V3 (SPEC-M2 §3.4). Flyway e o dono do schema; Hibernate roda
 * {@code ddl-auto=validate}, entao esta entidade deve <b>casar exatamente</b> V1+V3 (§12) — as
 * colunas nascem pela migracao, nunca pelo Hibernate.
 *
 * <p>{@code unidadeMedida} e mapeada como {@code String} (mirror da V1 {@code VARCHAR(20)}; o CHECK
 * do enum {@code un,kg,saco,m3,l,g} vive na V3 + Bean Validation no DTO — AD-SQ-31). Valores
 * {@code NUMERIC} viram {@code BigDecimal}: {@code estoqueMinimo}/{@code estoqueAtual} sao
 * {@code NUMERIC(14,3)} NOT NULL; {@code preco} e {@code NUMERIC(14,2)} anulavel (AD-SQ-28).
 *
 * <p>{@code criadoEm} e {@code insertable=false, updatable=false} (valor do {@code DEFAULT now()}).
 * {@code atualizadoEm} e {@code insertable=false, updatable=true}: o banco seta no insert e o
 * servico estampa {@code now()} no PUT/movimentacao (SPEC-M2 §4 — aprovado no gate). {@code estoque
 * Atual} so muda por movimentacao (AD-SQ-30); o CRUD nunca o toca.
 */
@Entity
@Table(name = "produto")
public class Produto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nome", nullable = false, length = 150)
    private String nome;

    @Column(name = "descricao")
    private String descricao;

    @Column(name = "unidade_medida", nullable = false, length = 20)
    private String unidadeMedida;

    @Column(name = "estoque_minimo", nullable = false, precision = 14, scale = 3)
    private BigDecimal estoqueMinimo;

    @Column(name = "estoque_atual", nullable = false, precision = 14, scale = 3)
    private BigDecimal estoqueAtual;

    @Column(name = "preco", precision = 14, scale = 2)
    private BigDecimal preco;

    @Column(name = "imagem_url", length = 1000)
    private String imagemUrl;

    /**
     * Metadados leves da imagem no banco (V5/AD-SQ-38). A coluna {@code imagem BYTEA} da V5 e
     * <b>deliberadamente NAO mapeada</b> aqui: {@code ddl-auto=validate} so cobra as colunas mapeadas,
     * entao o binario fica fora de {@code findAll}/{@code findById} (lista/detalhe nunca o materializam)
     * — o invariante de performance do M3. O bytea e lido/escrito so por queries nativas dedicadas em
     * {@link com.floricultura.api.repository.ProdutoRepository}. {@code imagemContentType != null}
     * significa "tem imagem no banco" (base do {@code temImagem} — SPEC-M3 §3.5).
     */
    @Column(name = "imagem_content_type", length = 100)
    private String imagemContentType;

    @Column(name = "imagem_filename", length = 255)
    private String imagemFilename;

    @Column(name = "ativo", nullable = false)
    private boolean ativo;

    // ---- Atributos botanicos ESCALARES (M6/V12, SPEC-M6 §3.5) --------------------------------
    // Mirror fiel da V12 (mesma convencao de `unidade_medida`/AD-SQ-31): o enum e String ASCII e o
    // CHECK vive no banco + Bean Validation no DTO. Todos ANULAVEIS (P12) — produto pre-M6 continua
    // valido com os 3 nulos, sem backfill. Rotulo pt-BR e responsabilidade do front.

    /** {@code MUDA|JOVEM|ADULTA} ou {@code null} (nao informado) — {@code ck_produto_caracteristica}. */
    @Column(name = "caracteristica", length = 10)
    private String caracteristica;

    /**
     * Altura da planta <b>sempre em centimetros inteiros</b>, 1..10000 (R12/P1 — nao existe coluna
     * {@code altura_unidade}; a conversao m↔cm e do front). So pode existir com {@code caracteristica
     * ∈ {JOVEM, ADULTA}} (R13): o {@code ck_produto_altura_exige_porte} NULL-safe e a segunda linha de
     * defesa — o {@code ProdutoService} valida <b>antes</b> do banco para devolver 400 e nao 500 (§12 #15).
     */
    @Column(name = "altura_cm")
    private Integer alturaCm;

    /** {@code TOXICA|NAO_TOXICA} ou {@code null} = <b>nao informado</b> (tri-estado por ausencia — R8/P2). */
    @Column(name = "toxicidade", length = 12)
    private String toxicidade;

    /**
     * {@code sazonal} = existe ao menos um vinculo N:N em {@code evento_produto} (M4/AD-SQ-44). Mapeado
     * como {@link Formula} (subselect SQL <b>read-only</b>, NAO coluna) — o {@code ddl-auto=validate}
     * cobra apenas colunas reais, entao isto <b>nao</b> exige uma coluna {@code sazonal} (que a V6
     * dropou do stub). Booleano leve: uma unica query por pagina, sem materializar colecao nem o
     * {@code bytea} de imagem (AD-SQ-38/FC-09). Somente leitura — nao ha setter.
     */
    @Formula("(exists (select 1 from evento_produto ep where ep.produto_id = id))")
    private boolean sazonal;

    @Column(name = "criado_em", nullable = false, insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "atualizado_em", nullable = false, insertable = false)
    private Instant atualizadoEm;

    protected Produto() {
        // Construtor exigido pelo JPA.
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public String getUnidadeMedida() {
        return unidadeMedida;
    }

    public void setUnidadeMedida(String unidadeMedida) {
        this.unidadeMedida = unidadeMedida;
    }

    public BigDecimal getEstoqueMinimo() {
        return estoqueMinimo;
    }

    public void setEstoqueMinimo(BigDecimal estoqueMinimo) {
        this.estoqueMinimo = estoqueMinimo;
    }

    public BigDecimal getEstoqueAtual() {
        return estoqueAtual;
    }

    public void setEstoqueAtual(BigDecimal estoqueAtual) {
        this.estoqueAtual = estoqueAtual;
    }

    public BigDecimal getPreco() {
        return preco;
    }

    public void setPreco(BigDecimal preco) {
        this.preco = preco;
    }

    public String getImagemUrl() {
        return imagemUrl;
    }

    public void setImagemUrl(String imagemUrl) {
        this.imagemUrl = imagemUrl;
    }

    public String getImagemContentType() {
        return imagemContentType;
    }

    public void setImagemContentType(String imagemContentType) {
        this.imagemContentType = imagemContentType;
    }

    public String getImagemFilename() {
        return imagemFilename;
    }

    public void setImagemFilename(String imagemFilename) {
        this.imagemFilename = imagemFilename;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public String getCaracteristica() {
        return caracteristica;
    }

    public void setCaracteristica(String caracteristica) {
        this.caracteristica = caracteristica;
    }

    public Integer getAlturaCm() {
        return alturaCm;
    }

    public void setAlturaCm(Integer alturaCm) {
        this.alturaCm = alturaCm;
    }

    public String getToxicidade() {
        return toxicidade;
    }

    public void setToxicidade(String toxicidade) {
        this.toxicidade = toxicidade;
    }

    /** {@code true} se o produto tem ao menos um vinculo em {@code evento_produto} (@Formula, M4). */
    public boolean isSazonal() {
        return sazonal;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public Instant getAtualizadoEm() {
        return atualizadoEm;
    }

    public void setAtualizadoEm(Instant atualizadoEm) {
        this.atualizadoEm = atualizadoEm;
    }
}
