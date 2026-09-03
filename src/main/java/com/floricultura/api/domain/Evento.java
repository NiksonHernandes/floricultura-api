package com.floricultura.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Espelho fiel (SPEC-M4 §8) da tabela {@code evento} evoluida na V6 (SPEC-M4 §3.1/AD-SQ-43). Flyway e
 * o dono do schema; Hibernate roda {@code ddl-auto=validate}, entao esta entidade deve <b>casar
 * exatamente</b> a V6 (§12) — as colunas nascem pela migracao, nunca pelo Hibernate.
 *
 * <p>{@code tipo} e mapeada como {@code String} (mirror da V6 {@code VARCHAR(20)}; o CHECK do enum
 * {@code COMEMORATIVA,FEIRA,BENEFICENTE,ENCOMENDA_CLIENTE} vive na V6 + Bean Validation no DTO —
 * AD-SQ-31). {@code dataInicio}/{@code dataFim} sao {@code DATE} → {@link LocalDate} (sem hora);
 * {@code dataFim} anulavel = evento de <b>data unica</b> (AD-SQ-43). {@code repeteTodoAno} = recorrencia
 * anual.
 *
 * <p>{@code criadoEm} e {@code insertable=false, updatable=false} (valor do {@code DEFAULT now()}).
 * {@code atualizadoEm} e {@code insertable=false, updatable=true}: o banco seta no insert e o servico
 * estampa {@code now()} no PUT (mesmo padrao de {@code Produto} — SPEC-M2 §4).
 */
@Entity
@Table(name = "evento")
public class Evento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nome", nullable = false, length = 150)
    private String nome;

    @Column(name = "tipo", nullable = false, length = 20)
    private String tipo;

    @Column(name = "data_inicio", nullable = false)
    private LocalDate dataInicio;

    @Column(name = "data_fim")
    private LocalDate dataFim;

    @Column(name = "repete_todo_ano", nullable = false)
    private boolean repeteTodoAno;

    @Column(name = "descricao")
    private String descricao;

    @Column(name = "criado_em", nullable = false, insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "atualizado_em", nullable = false, insertable = false)
    private Instant atualizadoEm;

    protected Evento() {
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

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public LocalDate getDataInicio() {
        return dataInicio;
    }

    public void setDataInicio(LocalDate dataInicio) {
        this.dataInicio = dataInicio;
    }

    public LocalDate getDataFim() {
        return dataFim;
    }

    public void setDataFim(LocalDate dataFim) {
        this.dataFim = dataFim;
    }

    public boolean isRepeteTodoAno() {
        return repeteTodoAno;
    }

    public void setRepeteTodoAno(boolean repeteTodoAno) {
        this.repeteTodoAno = repeteTodoAno;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
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
