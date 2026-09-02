package com.floricultura.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

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

    @Column(name = "ativo", nullable = false)
    private boolean ativo;

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

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
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
