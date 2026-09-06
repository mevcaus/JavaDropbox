package com.javadropbox.javadropbox.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

/**
 * Signs and validates the JWTs used by public share links. The token's only
 * claim is the relative path being shared, so anyone holding a valid token
 * can download that one path until it expires &mdash; no server-side state
 * is kept.
 */
@Service
public class ShareTokenService {

    private static final String PATH_CLAIM = "path";

    private final SecretKey signingKey;

    public ShareTokenService(@Value("${app.share.jwt-secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
    }

    public String generateToken(String relativePath, long expirationMinutes) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + Duration.ofMinutes(expirationMinutes).toMillis());

        return Jwts.builder()
                .claim(PATH_CLAIM, relativePath)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * @throws JwtException if the token is malformed, tampered with, or expired
     */
    public String resolvePath(String token) throws JwtException {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.get(PATH_CLAIM, String.class);
    }
}
