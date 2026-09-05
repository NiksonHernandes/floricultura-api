package com.floricultura.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Espelho fiel (SPEC-M5 §3.2) da tabela {@code fornecedor} evoluida na V9 (§3.1/AD-SQ-58) para o
 * conjunto minimo LGPD — irma de {@link Cliente} (mesmo padrao, schema independente). Flyway e o dono do
 * schema; Hibernate roda {@code ddl-auto=validate}, entao esta entidade deve <b>casar exatamente</b> a
 * V9 (§12) — as colunas {@code tipo_pessoa}/{@code documento}/{@code endereco} foram <b>dropadas</b> pela
 * V9 e por isso <b>nao</b> aparecem aqui.
 *
 * <p><b>Nao</b> mapeia colecao de vinculos: na revisao 2026-09-04 (AD-SQ-65) o vinculo fornecedor↔produto
 * deixou de ser junção N:N editavel e passou a ser <b>derivado da movimentacao</b> (ENTRADAS). A lista
 * nunca materializa o vinculo (AD-SQ-38); o {@code produtoIds} derivado do detalhe volta em RB-3/RB-4.
 *
 * <p>{@code criadoEm} e {@code insertable=false, updatable=false} (valor do {@code DEFAULT now()}).
 * {@code atualizadoEm} e {@code insertable=false, updatable=true}: o banco seta no insert e o servico
 * estampa {@code now()} no PUT. Construtor {@code protected} + {@link FornecedorFactory} no mesmo pacote.
 */
@Entity
@Table(name = "fornecedor")
public class Fornecedor {

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

    protected Fornecedor() {
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
