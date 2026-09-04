package com.floricultura.api.repository;

import com.floricultura.api.domain.Evento;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Acesso a persistencia de {@link Evento} (SPEC-M4 §8). O {@code findAll(Pageable)} herdado cobre a
 * listagem paginada/ordenada ({@code dataInicio ASC, nome ASC}) e {@code findByNomeContainingIgnoreCase}
 * implementa o filtro {@code ILIKE '%nome%'} (case-insensitive, substring) do contrato §3.2 — mesmo
 * padrao ja usado em {@code ProdutoRepository}/{@code UsuarioRepository}. O {@code existsById} herdado
 * serve a validacao referencial de {@code eventoIds} no vinculo N:N (T-M4-3).
 */
@Repository
public interface EventoRepository extends JpaRepository<Evento, Long> {

    Page<Evento> findByNomeContainingIgnoreCase(String nome, Pageable pageable);

    /**
     * Carrega TODOS os eventos para o calculo on-read de "proximos" (T-M4-7, §4.3). A varredura O(n) e
     * <b>intencional</b> (tabela {@code evento} pequena; sem job/agendador — compativel com o cold start
     * do host gratis, §9). Metodo dedicado (em vez de {@code findAll()}) para deixar o proposito
     * explicito — a paginacao obrigatoria (CA-23) vale para a lista GLOBAL de movimentacoes, nao aqui.
     */
    @Query("select e from Evento e")
    List<Evento> listarTodosParaAlerta();
}
