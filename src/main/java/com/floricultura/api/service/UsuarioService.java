package com.floricultura.api.service;

import com.floricultura.api.domain.Usuario;
import com.floricultura.api.domain.UsuarioFactory;
import com.floricultura.api.repository.UsuarioRepository;
import com.floricultura.api.web.dto.CriarUsuarioRequest;
import com.floricultura.api.web.dto.UsuarioResponse;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de gestao de usuarios do M1 — criar/listar/detalhar (SPEC-M1 §3.2/§4, CA-7/CA-8). Consome o
 * {@link UsuarioRepository} (T-M1-1) e o {@link PasswordEncoder} (BCrypt, bean do M0). O RBAC
 * ({@code ROLE_ADMIN} em {@code /usuarios/**}) e garantido pelo {@code SecurityConfig} do T-M1-2 —
 * este servico so trata a regra de negocio. Status/reset e a regra do ultimo-admin ficam no T-M1-5.
 *
 * <p><b>Segredo (§9):</b> {@code senha_hash} nunca sai em DTO ({@link UsuarioResponse} nao o carrega)
 * e a senha em texto so vira {@code PasswordEncoder.encode} — jamais logada.
 *
 * <p>{@code UsuarioRepository} injetado como {@link Lazy} (mesmo padrao do {@link AuthService}/filtro
 * do T-M1-2): os smokes do M0 ({@code contextLoads}, {@code HealthCheckControllerTest}) sobem sem JPA
 * — o repo so e resolvido quando ha chamada a {@code /usuarios} (nunca naqueles testes), preservando
 * as suites do M0.
 */
@Service
public class UsuarioService {

    /** Papel fixo na criacao via API (decisao do dono, 2026-09-01 — §3.2/AD-SQ-19): nunca cria ADMIN. */
    private static final String ROLE_CRIACAO = "USER";

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    public UsuarioService(
            @Lazy UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Cria um usuario (CA-7). Checa unicidade de e-mail <b>antes</b> do insert →
     * {@link EmailJaCadastradoException} (409 amigavel); grava {@code senha_hash} BCrypt; {@code role}
     * <b>sempre {@code USER}</b> (decisao do dono, 2026-09-01 — §3.2/AD-SQ-19: a API nunca cria ADMIN);
     * {@code ativo=true} e {@code senha_provisoria=true} (§4). Devolve {@link UsuarioResponse} sem
     * {@code senha_hash}.
     *
     * <p><b>Sem {@code @Transactional} aqui de proposito:</b> como {@code open-in-view=false}, o
     * {@code save} e a releitura correm em contextos de persistencia distintos, e so a releitura traz
     * {@code criado_em} (coluna {@code insertable=false}, vinda do {@code DEFAULT now()} do banco —
     * T-M1-1). A corrida entre a checagem e o insert e coberta pela constraint {@code uq_usuario_email}
     * (V1) → {@code DataIntegrityViolationException} → 409 no {@code GlobalExceptionHandler} do M0 (§4).
     */
    public UsuarioResponse criar(CriarUsuarioRequest req) {
        if (usuarioRepository.findByEmail(req.email()).isPresent()) {
            throw new EmailJaCadastradoException();
        }
        Usuario usuario = UsuarioFactory.novo(
                req.nome(),
                req.email(),
                passwordEncoder.encode(req.senha()),
                ROLE_CRIACAO);
        Long id = usuarioRepository.save(usuario).getId();
        return usuarioRepository.findById(id)
                .map(UsuarioResponse::de)
                .orElseThrow(UsuarioNaoEncontradoException::new);
    }

    /** Lista todos os usuarios ordenados por {@code nome} (CA-8), sem vazar {@code senha_hash} (§9). */
    @Transactional(readOnly = true)
    public List<UsuarioResponse> listar() {
        return usuarioRepository.findAll(Sort.by(Sort.Direction.ASC, "nome")).stream()
                .map(UsuarioResponse::de)
                .toList();
    }

    /** Detalha um usuario por id (CA-8); inexistente → {@link UsuarioNaoEncontradoException} (404). */
    @Transactional(readOnly = true)
    public UsuarioResponse detalhar(Long id) {
        return usuarioRepository.findById(id)
                .map(UsuarioResponse::de)
                .orElseThrow(UsuarioNaoEncontradoException::new);
    }
}
