package com.tokengen.service;

import com.tokengen.config.ClientRegistry;
import com.tokengen.config.ClientRegistry.ClientConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Issues signed JWTs for any registered client app.
 *
 * Token anatomy:
 *   sub  = verified email (lowercase)
 *   iss  = "interview-bank-token-generator"
 *   aud  = clientId  ← consuming app checks this matches its own ID
 *   jti  = UUID      ← consuming app stores this to prevent replay
 *   iat  = issued-at
 *   exp  = issued-at + client.tokenTtlHours
 *   ...  = any custom claims from client config (role, tier, plan, etc.)
 *
 * Consuming app verification checklist:
 *   1. Verify HS256 signature with the shared JWT_SECRET
 *   2. Check aud == your app's clientId
 *   3. Check exp not passed
 *   4. Check jti not already used in your DB (one-time use)
 */
@Service
public class TokenIssuerService {

    private static final Logger log = LoggerFactory.getLogger(TokenIssuerService.class);

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.issuer}")
    private String issuer;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Issue a token for the given email and client.
     *
     * @param verifiedEmail email that passed OTP verification
     * @param client        the registered client app config from application.yml
     */
    public String issueToken(String verifiedEmail, ClientConfig client) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + (long) client.getTokenTtlHours() * 3600 * 1000);

        var builder = Jwts.builder()
                .subject(verifiedEmail.toLowerCase())
                .issuer(issuer)
                .audience().add(client.getId()).and()  // aud claim
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiry);

        // Embed all custom claims from application.yml
        Map<String, String> customClaims = client.getClaims();
        if (customClaims != null) {
            customClaims.forEach(builder::claim);
        }

        String token = builder.signWith(signingKey).compact();

        log.info("Token issued: clientId={} email={} ttlHours={} claims={}",
                client.getId(), verifiedEmail, client.getTokenTtlHours(), customClaims);
        return token;
    }
}
