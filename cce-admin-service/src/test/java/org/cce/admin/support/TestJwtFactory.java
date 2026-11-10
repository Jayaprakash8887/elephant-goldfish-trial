package org.cce.admin.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Signs test tokens with an RSA key pair generated once per test run. Defaults: the test issuer and
 * audience of {@code application-test.yml}, a random {@code sub}, expiry in 5 minutes, no roles and
 * no {@code auth_time}.
 */
public final class TestJwtFactory {

    public static final String ISSUER = "https://test-issuer.local/realms/cce";
    public static final String AUDIENCE = "gateway-service";

    private static final KeyPair KEY_PAIR = generateKeyPair();
    private static final KeyPair OTHER_KEY_PAIR = generateKeyPair();

    private TestJwtFactory() {
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEY_PAIR.getPublic();
    }

    public static Builder token() {
        return new Builder();
    }

    public static final class Builder {

        private String issuer = ISSUER;
        private List<String> audience = List.of(AUDIENCE);
        private String subject = UUID.randomUUID().toString();
        private Instant issuedAt = Instant.now();
        private Instant expiresAt = Instant.now().plusSeconds(300);
        private List<String> roles;
        private Instant authTime;
        private final Map<String, Object> extraClaims = new LinkedHashMap<>();
        private boolean otherKey;

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(String... audience) {
            this.audience = List.of(audience);
            return this;
        }

        public Builder subject(UUID subject) {
            this.subject = subject.toString();
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder issuedAt(Instant issuedAt) {
            this.issuedAt = issuedAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /** Written as {@code realm_access.roles}, the default claim path. */
        public Builder roles(String... roles) {
            this.roles = List.of(roles);
            return this;
        }

        public Builder authTime(Instant authTime) {
            this.authTime = authTime;
            return this;
        }

        public Builder claim(String name, Object value) {
            extraClaims.put(name, value);
            return this;
        }

        /** Signs with a different key than the one the test decoder trusts. */
        public Builder signedWithOtherKey() {
            this.otherKey = true;
            return this;
        }

        public String build() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt));
            if (roles != null) {
                claims.claim("realm_access", Map.of("roles", roles));
            }
            if (authTime != null) {
                claims.claim("auth_time", authTime.getEpochSecond());
            }
            extraClaims.forEach(claims::claim);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims.build());
            try {
                jwt.sign(new RSASSASigner((otherKey ? OTHER_KEY_PAIR : KEY_PAIR).getPrivate()));
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
            return jwt.serialize();
        }

        public String bearer() {
            return "Bearer " + build();
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
