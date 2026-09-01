package com.floricultura.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Espelho fiel (SPEC-M1 §8) da tabela {@code usuario} — schema V1 (SPEC-M0 §3.6) + coluna
 * {@code senha_provisoria} da V2 (SPEC-M1 §3.5). Flyway e o dono do schema; Hibernate roda
 * {@code ddl-auto=validate}, entao esta entidade deve <b>casar exatamente</b> V1+V2 (§12) — a
 * coluna nasce pela migracao, nunca pelo Hibernate.
 *
 * <p>{@code role} e mapeada como {@code String} (mirror da V1 {@code VARCHAR(20)} CHECK ADMIN/USER),
 * decisao do sub-gate humano do M1. {@code senhaHash} nunca trafega em DTO/resposta/log (§9).
 * {@code criadoEm}/{@code atualizadoEm} sao {@code insertable=false, updatable=false}: o valor vem
 * do {@code DEFAULT now()} do banco (T-M1-1 nao escreve usuarios; escrita entra nas tasks seguintes).
 */
@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nome", nullable = false, length = 120)
    private String nome;

    @Column(name = "email", nullable = false, length = 180, unique = true)
    private String email;

    @Column(name = "senha_hash", nullable = false, length = 255)
    private String senhaHash;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "ativo", nullable = false)
    private boolean ativo;

    @Column(name = "senha_provisoria", nullable = false)
    private boolean senhaProvisoria;

    @Column(name = "criado_em", nullable = false, insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "atualizado_em", nullable = false, insertable = false, updatable = false)
    private Instant atualizadoEm;

    protected Usuario() {
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getSenhaHash() {
        return senhaHash;
    }

    public void setSenhaHash(String senhaHash) {
        this.senhaHash = senhaHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public boolean isSenhaProvisoria() {
        return senhaProvisoria;
    }

    public void setSenhaProvisoria(boolean senhaProvisoria) {
        this.senhaProvisoria = senhaProvisoria;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public Instant getAtualizadoEm() {
        return atualizadoEm;
    }
}
