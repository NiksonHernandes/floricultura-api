package com.floricultura.api.service;

import com.floricultura.api.domain.Usuario;
import com.floricultura.api.repository.UsuarioRepository;
import com.floricultura.api.web.dto.LoginRequest;
import com.floricultura.api.web.dto.LoginResponse;
import com.floricultura.api.web.dto.MeResponse;
import com.floricultura.api.web.dto.TrocarSenhaRequest;
import com.floricultura.api.web.dto.UsuarioSessaoResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regra de autenticacao do M1 (SPEC-M1 §3.2/§4, CA-1/CA-2/CA-3/CA-10). Consome o {@link JwtService}
 * (emissao de token — T-M1-2), o {@link UsuarioRepository} (T-M1-1) e o {@link PasswordEncoder}
 * (BCrypt, bean do M0). Nao loga senha nem token (§9).
 *
 * <p><b>Anti-enumeracao (nucleo de CA-2/CA-3):</b> e-mail inexistente, senha errada e conta inativa
 * lancam a MESMA {@link CredenciaisInvalidasException} sem carga distintiva → resposta 401 identica.
 * A comparacao e sempre {@link PasswordEncoder#matches} (nunca hash como String — §9). Timing nao e
 * otimizado (aceitavel para app interno — §9).
 *
 * <p>{@code UsuarioRepository} injetado como {@link Lazy} (mesmo padrao do
 * {@code JwtAuthenticationFilter} do T-M1-2): os smokes do M0 ({@code contextLoads},
 * {@code HealthCheckControllerTest}) sobem sem JPA — o repo so e resolvido quando ha chamada de auth
 * (nunca naqueles testes), preservando as suites do M0.
 */
@Service
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            @Lazy UsuarioRepository usuarioRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Autentica por e-mail + senha e emite o JWT (CA-1). Falha (inexistente/senha errada/inativo) →
     * {@link CredenciaisInvalidasException} identica (CA-2/CA-3), sem token no corpo.
     */
    @Transactional(readOnly = true)
    public LoginResponse autenticar(LoginRequest req) {
        Usuario usuario = usuarioRepository.findByEmail(req.email())
                .orElseThrow(CredenciaisInvalidasException::new);
        if (!passwordEncoder.matches(req.senha(), usuario.getSenhaHash())) {
            throw new CredenciaisInvalidasException();
        }
        if (!usuario.isAtivo()) {
            throw new CredenciaisInvalidasException();
        }
        String token = jwtService.gerarToken(usuario.getId());
        return new LoginResponse(
                token, TOKEN_TYPE, jwtService.getTtlSeconds(), UsuarioSessaoResponse.de(usuario));
    }

    /**
     * Perfil do autenticado (CA-4). Recarrega por id (o filtro ja validou existencia + {@code ativo}),
     * garantindo role/estado frescos do banco (§3.3). Se sumir entre filtro e servico → 401.
     */
    @Transactional(readOnly = true)
    public MeResponse perfil(Long usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(CredenciaisInvalidasException::new);
        return MeResponse.de(usuario);
    }

    /**
     * Troca da propria senha (CA-10). Valida {@code senhaAtual} por BCrypt (errada → 400 campo
     * {@code senhaAtual}, sem deslogar); grava a nova como BCrypt e zera {@code senha_provisoria}.
     * {@code atualizado_em} nao avanca (coluna {@code updatable=false} sem trigger — nao e requisito
     * do contrato §3.2, que responde 204 sem corpo).
     */
    @Transactional
    public void trocarSenha(Long usuarioId, TrocarSenhaRequest req) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(CredenciaisInvalidasException::new);
        if (!passwordEncoder.matches(req.senhaAtual(), usuario.getSenhaHash())) {
            throw new SenhaAtualIncorretaException();
        }
        usuario.setSenhaHash(passwordEncoder.encode(req.novaSenha()));
        usuario.setSenhaProvisoria(false);
        usuarioRepository.save(usuario);
    }
}
