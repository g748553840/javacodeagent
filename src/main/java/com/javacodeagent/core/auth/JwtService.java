package com.javacodeagent.core.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;

/**
 * JWT 令牌生成与验证服务。
 *
 * <p>配置方式（application.yml）：
 * <pre>
 *   security:
 *     jwt:
 *       secret: your-256-bit-secret   # 留空则禁用 JWT 认证
 *       ttl-hours: 24                 # 令牌有效期（默认 24 小时）
 * </pre>
 *
 * <p>userId 存储在 JWT 的 {@code sub}（subject）claim 中，
 * 其他自定义 claim 可通过 {@link #generateToken(String, java.util.Map)} 扩展。
 */
@Slf4j
@Service
public class JwtService {

    /** HMAC-SHA256 签名密钥最小字节数（256 bit）。 */
    private static final int HMAC_MIN_KEY_BYTES = 32;

    private final SecretKey signingKey;
    private final Duration tokenTtl;

    public JwtService(
            @Value("${security.jwt.secret:}") String secret,
            @Value("${security.jwt.ttl-hours:24}") int ttlHours) {
        if (secret == null || secret.isBlank()) {
            this.signingKey = null;
            log.info("JWT auth disabled (security.jwt.secret not configured)");
        } else {
            byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
            if (raw.length < HMAC_MIN_KEY_BYTES) {
                log.warn("security.jwt.secret is only {} bytes (< {}). Padding with zero bytes weakens " +
                         "the signing key. Use a secret of at least {} characters.", raw.length, HMAC_MIN_KEY_BYTES, HMAC_MIN_KEY_BYTES);
            }
            byte[] keyBytes = raw.length >= HMAC_MIN_KEY_BYTES ? raw : Arrays.copyOf(raw, HMAC_MIN_KEY_BYTES);
            this.signingKey = Keys.hmacShaKeyFor(keyBytes);
            log.info("JWT auth enabled, token TTL = {} hours", ttlHours);
        }
        this.tokenTtl = Duration.ofHours(ttlHours);
    }

    /** Returns true when JWT auth is configured and active. */
    public boolean isEnabled() {
        return signingKey != null;
    }

    /**
     * Generate a signed JWT with {@code userId} as the subject claim.
     *
     * @throws IllegalStateException if JWT is not configured
     */
    public String generateToken(String userId) {
        if (!isEnabled()) throw new IllegalStateException("JWT not configured — set security.jwt.secret");
        Date now = new Date();
        return Jwts.builder()
            .subject(userId)
            .issuedAt(now)
            .expiration(new Date(now.getTime() + tokenTtl.toMillis()))
            .signWith(signingKey)
            .compact();
    }

    /**
     * Validate the token and extract the userId (subject claim).
     *
     * @return userId string, or {@code null} if the token is invalid or expired
     */
    public String extractUserId(String token) {
        if (!isEnabled() || token == null || token.isBlank()) return null;
        try {
            Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            return claims.getSubject();
        } catch (JwtException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return null;
        }
    }
}
