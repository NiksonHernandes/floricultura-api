package com.floricultura.api.repository;

import com.floricultura.api.domain.Usuario;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Acesso a persistencia de {@link Usuario} (SPEC-M1 §8). Os metodos derivados cobrem o essencial do
 * M1: {@code findByEmail} (login por e-mail — AD-SQ-14; carga do usuario no filtro JWT — §3.4) e
 * {@code countByRoleAndAtivoTrue} (protecao do ultimo ADMIN ativo — §4/AD-SQ-19).
 */
@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmail(String email);

    long countByRoleAndAtivoTrue(String role);
}
