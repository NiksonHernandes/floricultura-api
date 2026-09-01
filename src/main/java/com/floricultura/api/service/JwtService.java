package com.floricultura.api.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Emissao e validacao de JWT stateless (SPEC-M1 §3.3, CA-1/CA-4). Algoritmo <b>HS256</b> (HMAC-SHA256)
 * com segredo simetrico vindo de {@code app.jwt.secret} (env {@code APP_JWT_SECRET}) — <b>sem default
 * em config versionada</b>: o servico <b>falha ao subir</b> se o segredo estiver ausente (placeholder
 * nao resolve) ou tiver menos de 32 bytes / 256 bits (§9). O TTL vem de {@code app.jwt.ttl-seconds}
 * (default 3600).
 *
 * <p>Biblioteca <b>Nimbus</b> via {@code spring-security-oauth2-jose} (gerenciada pelo BOM do Boot
 * 4.1.1 — AD-SQ-13): {@link NimbusJwtEncoder} com {@link ImmutableSecret} e {@link NimbusJwtDecoder}
 * {@code withSecretKey(...)}. O decoder ja valida assinatura + expiracao (validador de timestamp
 * default do Nimbus); token invalido/expirado/adulterado lanca {@link JwtException}.
 *
 * <p><b>Claims (§3.3):</b> {@code iss=floricultura-api}, {@code sub=usuario.id} (string), {@code iat},
 * {@code exp=iat+ttl}, {@code jti=UUID}. <b>Deliberadamente ausentes</b> {@code role}/{@code email}/
 * {@code nome} e qualquer PII — a role e lida do banco a cada requisicao (AD-SQ-16). O token nunca e
 * logado (§9/§12).
 */
@Service
public class JwtService {

    private static final String ISSUER = "floricultura-api";
    private static final int MIN_SECRET_BYTES = 32; // 256 bits (HS256).

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final long ttlSeconds;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.ttl-seconds:3600}") long ttlSeconds) {
        byte[] key = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < MIN_SECRET_BYTES) {
            // Fail-fast (§3.3/§9): nunca subir com segredo ausente/curto. A mensagem NAO expoe o valor.
            throw new IllegalStateException(
                    "APP_JWT_SECRET ausente ou com menos de " + MIN_SECRET_BYTES
                            + " bytes (256 bits) — obrigatorio para HS256.");
        }
        SecretKey secretKey = new SecretKeySpec(key, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
        this.decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Emite um JWT HS256 assinado para o usuario informado (claims §3.3). O {@code sub} e o id como
     * string; nenhuma PII (role/email/nome) entra no token.
     *
     * @param usuarioId id estavel do usuario (vira o claim {@code sub})
     * @return o token compacto assinado (JWS)
     */
    public String gerarToken(Long usuarioId) {
        Instant agora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(usuarioId))
                .issuedAt(agora)
                .expiresAt(agora.plusSeconds(ttlSeconds))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Decodifica e valida o token (assinatura + expiracao). Token invalido, expirado ou adulterado
     * lanca {@link JwtException} — o chamador (filtro, T-M1-2b) trata como nao-autenticado.
     *
     * @param token o JWS compacto (sem o prefixo {@code Bearer })
     * @return o {@link Jwt} decodificado com os claims
     * @throws JwtException se assinatura/expiracao/formato forem invalidos
     */
    public Jwt decodificar(String token) {
        return decoder.decode(token);
    }

    /** TTL em segundos (para o {@code expiresIn} da resposta de login — T-M1-3). */
    public long getTtlSeconds() {
        return ttlSeconds;
    }
}
