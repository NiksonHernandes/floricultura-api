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
 * Espelho fiel (SPEC-M2 §8) da tabela {@code movimentacao_estoque} — ledger insert-only do V1
 * (SPEC-M0 §3.6). Flyway e o dono do schema; Hibernate roda {@code ddl-auto=validate}, entao esta
 * entidade deve <b>casar exatamente</b> a V1 (§12).
 *
 * <p>Ledger imutavel (AD-SQ-8): as linhas so entram por INSERT; a trigger de banco
 * {@code trg_movimentacao_imutavel} rejeita UPDATE/DELETE (a V4/AD-SQ-34 libera exclusivamente o
 * cascade {@code produto_id}->NULL do hard delete de produto). Correcao de estoque = <b>nova</b>
 * movimentacao (AJUSTE), nunca edicao de linha.
 *
 * <p>{@code produtoId} e {@code usuarioId} sao mapeados como escalares {@code Long} anulaveis (FK
 * {@code ON DELETE SET NULL}) — sem {@code @ManyToOne} para nao acoplar cascade/lazy a um ledger
 * insert-only. {@code produtoNome} e o snapshot que preserva o historico apos o delete do produto.
 * {@code tipo} e {@code String} (mirror da V1 {@code VARCHAR(20)} CHECK ENTRADA/SAIDA/AJUSTE).
 * {@code criadoEm} e {@code insertable=false, updatable=false} (valor do {@code DEFAULT now()}).
 */
@Entity
@Table(name = "movimentacao_estoque")
public class MovimentacaoEstoque {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "produto_id")
    private Long produtoId;

    @Column(name = "produto_nome", nullable = false, length = 150)
    private String produtoNome;

    @Column(name = "tipo", nullable = false, length = 20)
    private String tipo;

    @Column(name = "quantidade", nullable = false, precision = 14, scale = 3)
    private BigDecimal quantidade;

    @Column(name = "quantidade_resultante", nullable = false, precision = 14, scale = 3)
    private BigDecimal quantidadeResultante;

    @Column(name = "motivo", length = 255)
    private String motivo;

    @Column(name = "usuario_id")
    private Long usuarioId;

    @Column(name = "criado_em", nullable = false, insertable = false, updatable = false)
    private Instant criadoEm;

    protected MovimentacaoEstoque() {
        // Construtor exigido pelo JPA.
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProdutoId() {
        return produtoId;
    }

    public void setProdutoId(Long produtoId) {
        this.produtoId = produtoId;
    }

    public String getProdutoNome() {
        return produtoNome;
    }

    public void setProdutoNome(String produtoNome) {
        this.produtoNome = produtoNome;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public BigDecimal getQuantidade() {
        return quantidade;
    }

    public void setQuantidade(BigDecimal quantidade) {
        this.quantidade = quantidade;
    }

    public BigDecimal getQuantidadeResultante() {
        return quantidadeResultante;
    }

    public void setQuantidadeResultante(BigDecimal quantidadeResultante) {
        this.quantidadeResultante = quantidadeResultante;
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String motivo) {
        this.motivo = motivo;
    }

    public Long getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(Long usuarioId) {
        this.usuarioId = usuarioId;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
