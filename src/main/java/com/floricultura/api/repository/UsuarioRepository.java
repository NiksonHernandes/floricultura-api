package com.floricultura.api.repository;

import com.floricultura.api.domain.Usuario;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Acesso a persistencia de {@link Usuario} (SPEC-M1 §8). Os metodos derivados cobrem o essencial do
 * M1: {@code findByEmail} (login por e-mail — AD-SQ-14; carga do usuario no filtro JWT — §3.4) e
 * {@code countByRoleAndAtivoTrue} (protecao do ultimo ADMIN ativo — §4/AD-SQ-19).
 *
 * <p><b>Retrofit M2 (T-M2-5 / AD-SQ-29):</b> o {@code findAll(Pageable)} herdado cobre a listagem
 * paginada/ordenada ({@code nome ASC}) e {@code findByNomeContainingIgnoreCase} implementa o filtro
 * {@code ILIKE '%nome%'} (case-insensitive, substring) do contrato de listagem reutilizavel (§3.3) —
 * o mesmo padrao ja usado no {@code ProdutoRepository} (T-M2-2). O {@code GET /usuarios} deixa de
 * devolver array e passa a {@code PaginaResponse<UsuarioResponse>} (muda o contrato do M1 — CA-21).
 */
@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmail(String email);

    long countByRoleAndAtivoTrue(String role);

    Page<Usuario> findByNomeContainingIgnoreCase(String nome, Pageable pageable);

    /**
     * Projecao escalar do nome do usuario para desnormalizar o autor da movimentacao (M4/T-M4-10,
     * AD-SQ-45): resolve {@code usuario_nome} do {@code @AuthenticationPrincipal} no INSERT do ledger,
     * sem carregar a entidade inteira. {@code null} se o id nao existir.
     */
    @Query("select u.nome from Usuario u where u.id = :id")
    String findNomeById(@Param("id") Long id);
}
