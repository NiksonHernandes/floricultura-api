package com.floricultura.api.repository;

import com.floricultura.api.domain.Evento;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
