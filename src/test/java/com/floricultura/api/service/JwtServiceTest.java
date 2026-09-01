package com.floricultura.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Unit do {@link JwtService} (SPEC-M1 §3.3, plano de testes §6 — CA-1/CA-4). Puro (sem contexto
 * Spring): instancia o servico direto com um segredo <b>descartavel de teste</b> (NAO e credencial
 * real; >= 32 bytes so para satisfazer o fail-fast do HS256).
 *
 * <p>Cobre: round-trip encode->decode dos claims {@code iss/sub/exp/jti}, rejeicao de token
 * <b>expirado</b> (token forjado com {@code exp} no passado, alem do clock-skew) e de <b>assinatura
 * invalida</b> (chave diferente no decoder).
 */
class JwtServiceTest {

    // Segredos test-only, NAO-segredo (nao sao credencial real): 40 bytes ASCII, minimo do HS256.
    private static final String SECRET_A =
            "test-only-nao-segredo-chave-A-0123456789";
    private static final String SECRET_B =
            "test-only-nao-segredo-chave-B-abcdefghij";
    private static final long TTL_1H = 3600L;

    @Test
    void gerarEDecodificar_roundTripDosClaims() {
        JwtService service = new JwtService(SECRET_A, TTL_1H);
        Instant antes = Instant.now();

        String token = service.gerarToken(42L);
        Jwt jwt = service.decodificar(token);

        // iss e a string fixa "floricultura-api" (§3.3), NAO uma URL — asserta pelo claim cru
        // (getIssuer() do Spring tentaria converter para URL e falharia).
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("floricultura-api");
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getId()).isNotBlank(); // jti (UUID)
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isNotNull();
        // exp = iat + ttl.
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds())
                .isEqualTo(TTL_1H);
        assertThat(jwt.getExpiresAt()).isAfter(antes);
    }

    @Test
    void gerarToken_naoCarregaRoleEmailNemNome() {
        JwtService service = new JwtService(SECRET_A, TTL_1H);

        Jwt jwt = service.decodificar(service.gerarToken(7L));

        // Minimizacao (§3.3): so id/iss/iat/exp/jti — sem PII no token.
        assertThat(jwt.getClaims()).doesNotContainKeys("role", "email", "nome", "name");
    }

    @Test
    void decodificar_tokenExpirado_lancaJwtException() {
        JwtService service = new JwtService(SECRET_A, TTL_1H);
        // Token forjado (mesma chave A) com exp 1h no passado — alem do clock-skew default (60s) do
        // NimbusJwtDecoder, entao a validacao de timestamp rejeita. Encodar via service com TTL
        // negativo nao serve: o encoder exige exp > iat.
        Instant agora = Instant.now();
        String tokenExpirado = forjarToken(SECRET_A, agora.minusSeconds(7200), agora.minusSeconds(3600));

        assertThatThrownBy(() -> service.decodificar(tokenExpirado))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void decodificar_assinaturaInvalida_lancaJwtException() {
        // Emite com a chave A; tenta validar com a chave B => assinatura nao confere.
        JwtService emissor = new JwtService(SECRET_A, TTL_1H);
        JwtService verificadorOutraChave = new JwtService(SECRET_B, TTL_1H);
        String token = emissor.gerarToken(1L);

        assertThatThrownBy(() -> verificadorOutraChave.decodificar(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void construtor_segredoCurto_falhaAoInstanciar() {
        // Fail-fast (§3.3/§9): segredo < 32 bytes nao sobe. A mensagem nao expoe o valor.
        assertThatThrownBy(() -> new JwtService("curto", TTL_1H))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
    }

    /** Forja um JWS HS256 com iat/exp arbitrarios usando a mesma lib (Nimbus) e a chave informada. */
    private static String forjarToken(String secret, Instant iat, Instant exp) {
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("floricultura-api")
                .subject("1")
                .issuedAt(iat)
                .expiresAt(exp)
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
