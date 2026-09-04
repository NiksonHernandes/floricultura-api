package com.floricultura.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Espelho fiel (SPEC-M5 §3.2) da tabela {@code cliente} evoluida na V9 (§3.1/AD-SQ-58) para o conjunto
 * minimo LGPD. Flyway e o dono do schema; Hibernate roda {@code ddl-auto=validate}, entao esta entidade
 * deve <b>casar exatamente</b> a V9 (§12) — as colunas {@code tipo_pessoa}/{@code documento}/{@code
 * endereco} foram <b>dropadas</b> pela V9 e por isso <b>nao</b> aparecem aqui.
 *
 * <p><b>Nao</b> mapeia a colecao de vinculos {@code cliente_produto} (N:N): o vinculo e lido/escrito por
 * query nativa dedicada (mesmo padrao de {@code Produto}↔{@code evento_produto}, AD-SQ-44) — a lista
 * nunca materializa a colecao (§3.3/§4.2).
 *
 * <p>{@code criadoEm} e {@code insertable=false, updatable=false} (valor do {@code DEFAULT now()}).
 * {@code atualizadoEm} e {@code insertable=false, updatable=true}: o banco seta no insert e o servico
 * estampa {@code now()} no PUT (mesmo padrao de {@code Evento}/{@code Produto}). Construtor
 * {@code protected} + {@link ClienteFactory} no mesmo pacote (padrao {@code EventoFactory}).
 */
@Entity
@Table(name = "cliente")
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nome", nullable = false, length = 150)
    private String nome;

    @Column(name = "telefone", length = 40)
    private String telefone;

    @Column(name = "email", length = 180)
    private String email;

    @Column(name = "observacoes", length = 500)
    private String observacoes;

    @Column(name = "criado_em", nullable = false, insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "atualizado_em", nullable = false, insertable = false)
    private Instant atualizadoEm;

    protected Cliente() {
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

    public String getTelefone() {
        return telefone;
    }

    public void setTelefone(String telefone) {
        this.telefone = telefone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getObservacoes() {
        return observacoes;
    }

    public void setObservacoes(String observacoes) {
        this.observacoes = observacoes;
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
