package com.floricultura.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Espelho fiel (SPEC-M6 §3.1/§3.2) da tabela {@code cor} criada pela V12 — Flyway e o dono do schema e o
 * Hibernate roda {@code ddl-auto=validate}, entao esta entidade casa exatamente a migracao.
 *
 * <p>{@code nome} guarda SEMPRE o valor CANONICO (§3.2.1/AD-SQ-90) produzido por
 * {@link Cores#canonizar(String)} — nao ha coluna de "nome de exibicao". {@code hex} e opcional.
 *
 * <p><b>Nao</b> mapeia a colecao {@code produto_cor}: a contagem de uso ({@code produtosVinculados}) vem
 * de query agrupada dedicada (1 por pagina — sem N+1, AD-SQ-38).
 *
 * <p>{@code criadoEm} e {@code insertable=false, updatable=false} e {@code atualizadoEm} e
 * {@code insertable=false} (valores do {@code DEFAULT now()}); o servico estampa {@code now()} no PUT.
 * Construtor {@code protected} + {@link CorFactory} no mesmo pacote (padrao {@code Fornecedor}).
 */
@Entity
@Table(name = "cor")
public class Cor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nome", nullable = false, length = 40)
    private String nome;

    @Column(name = "hex", length = 7)
    private String hex;

    @Column(name = "criado_em", nullable = false, insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "atualizado_em", nullable = false, insertable = false)
    private Instant atualizadoEm;

    protected Cor() {
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

    public String getHex() {
        return hex;
    }

    public void setHex(String hex) {
        this.hex = hex;
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
