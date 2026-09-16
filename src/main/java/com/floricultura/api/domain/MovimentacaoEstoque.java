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
 * insert-only. {@code produtoNome} e o snapshot que preserva o historico apos o delete do produto;
 * {@code usuarioNome} (V7/AD-SQ-45) e o snapshot analogo do <b>autor</b> (sobrevive ao delete do
 * usuario; {@code null} em linhas historicas pre-V7). {@code tipo} e {@code String} (mirror da V1
 * {@code VARCHAR(20)} CHECK ENTRADA/SAIDA/AJUSTE). {@code criadoEm} e {@code insertable=false,
 * updatable=false} (valor do {@code DEFAULT now()}).
 *
 * <p><b>Contraparte (V10/AD-SQ-64, RB-3):</b> {@code fornecedorId}/{@code clienteId} (FK anulavel
 * {@code ON DELETE SET NULL}) + os snapshots {@code fornecedorNome}/{@code clienteNome} (VARCHAR(150)),
 * mesmos escalares simples do padrao {@code usuarioNome} — o hard delete LGPD (FC-08) do cadastro anula
 * o {@code *_id} (cascade) e <b>preserva</b> o {@code *_nome} (a trigger V10 tolera essa anulacao e
 * mantem os snapshots imutaveis). ENTRADA pode ter fornecedor; SAIDA pode ter cliente; AJUSTE nenhum
 * (CHECK {@code ck_mov_*_tipo} + validacao do servico com {@code field}).
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

    @Column(name = "usuario_nome", length = 120)
    private String usuarioNome;

    @Column(name = "fornecedor_id")
    private Long fornecedorId;

    @Column(name = "fornecedor_nome", length = 150)
    private String fornecedorNome;

    @Column(name = "cliente_id")
    private Long clienteId;

    @Column(name = "cliente_nome", length = 150)
    private String clienteNome;

    @Column(name = "valor_unitario", precision = 14, scale = 2)
    private BigDecimal valorUnitario;

    @Column(name = "desconto_tipo", length = 12)
    private String descontoTipo;

    @Column(name = "desconto_valor", precision = 14, scale = 2)
    private BigDecimal descontoValor;

    @Column(name = "total_bruto", precision = 14, scale = 2)
    private BigDecimal totalBruto;

    @Column(name = "total_final", precision = 14, scale = 2)
    private BigDecimal totalFinal;

    @Column(name = "estorna_movimentacao_id")
    private Long estornaMovimentacaoId;

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

    public String getUsuarioNome() {
        return usuarioNome;
    }

    public void setUsuarioNome(String usuarioNome) {
        this.usuarioNome = usuarioNome;
    }

    public Long getFornecedorId() {
        return fornecedorId;
    }

    public void setFornecedorId(Long fornecedorId) {
        this.fornecedorId = fornecedorId;
    }

    public String getFornecedorNome() {
        return fornecedorNome;
    }

    public void setFornecedorNome(String fornecedorNome) {
        this.fornecedorNome = fornecedorNome;
    }

    public Long getClienteId() {
        return clienteId;
    }

    public void setClienteId(Long clienteId) {
        this.clienteId = clienteId;
    }

    public String getClienteNome() {
        return clienteNome;
    }

    public void setClienteNome(String clienteNome) {
        this.clienteNome = clienteNome;
    }

    public BigDecimal getValorUnitario() {
        return valorUnitario;
    }

    public void setValorUnitario(BigDecimal valorUnitario) {
        this.valorUnitario = valorUnitario;
    }

    public String getDescontoTipo() {
        return descontoTipo;
    }

    public void setDescontoTipo(String descontoTipo) {
        this.descontoTipo = descontoTipo;
    }

    public BigDecimal getDescontoValor() {
        return descontoValor;
    }

    public void setDescontoValor(BigDecimal descontoValor) {
        this.descontoValor = descontoValor;
    }

    public BigDecimal getTotalBruto() {
        return totalBruto;
    }

    public void setTotalBruto(BigDecimal totalBruto) {
        this.totalBruto = totalBruto;
    }

    public BigDecimal getTotalFinal() {
        return totalFinal;
    }

    public void setTotalFinal(BigDecimal totalFinal) {
        this.totalFinal = totalFinal;
    }

    public Long getEstornaMovimentacaoId() {
        return estornaMovimentacaoId;
    }

    public void setEstornaMovimentacaoId(Long estornaMovimentacaoId) {
        this.estornaMovimentacaoId = estornaMovimentacaoId;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
