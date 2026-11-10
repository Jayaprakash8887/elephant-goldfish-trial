package org.cce.admin.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The values the E2E checks run with (Phase 8). Reads {@code .env} from the working directory
 * ({@code KEY=VALUE}, resolving {@code ${…}} from earlier keys), with process environment variables
 * taking precedence. Provides the URLs of section 9.3, the per-run suffix (the last 8 digits of the
 * epoch seconds) and the names the scenarios create.
 *
 * <p>DESIGN-GAP: Phase 8 gives one name per scenario ({@code E2E_<SCENARIO>_<suffix>},
 * {@code e2e-<scenario>-<suffix>}), but S1, S2, S4 and S6 need two or more roles or users. Such a
 * scenario passes a label with a letter appended ({@code S1A}, {@code S1B}), so every name still
 * follows the pattern and stays unique within the run.
 */
public final class E2eEnvironment {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final E2eEnvironment INSTANCE = load(Path.of(".env"));

    /** The service, called directly on port 8085 (section 1, request path). */
    public static final String SERVICE_URL = "http://localhost:8085";
    /** Mailpit's web UI and API (section 9.3). */
    public static final String MAILPIT_URL = "http://localhost:8025";
    /** The local master admin of Keycloak, fixed in docker-compose.yml. */
    public static final String KEYCLOAK_MASTER_ADMIN = "admin";
    /** The public client the E2E checks get password-grant tokens from (section 9.2). */
    public static final String E2E_CLIENT_ID = "cce-e2e";

    private final Map<String, String> fileValues;
    private final String suffix;

    private E2eEnvironment(Map<String, String> fileValues, String suffix) {
        this.fileValues = fileValues;
        this.suffix = suffix;
    }

    public static E2eEnvironment get() {
        return INSTANCE;
    }

    private static E2eEnvironment load(Path envFile) {
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.isRegularFile(envFile)) {
            try {
                for (String rawLine : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = line.substring(0, eq).trim();
                    String value = resolve(line.substring(eq + 1).trim(), values);
                    values.put(key, value);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + envFile.toAbsolutePath(), e);
            }
        }
        long epochSeconds = Instant.now().getEpochSecond();
        String digits = Long.toString(epochSeconds);
        return new E2eEnvironment(values, digits.substring(digits.length() - 8));
    }

    /** Resolves {@code ${KEY}} from earlier keys, where a process variable wins over the file. */
    private static String resolve(String value, Map<String, String> earlier) {
        Matcher matcher = REFERENCE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = System.getenv(name) != null ? System.getenv(name) : earlier.getOrDefault(name, "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The value of {@code key}: the process environment first, then {@code .env}. Fails when unset. */
    public String value(String key) {
        String fromProcess = System.getenv(key);
        String value = fromProcess != null ? fromProcess : fileValues.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is not set (in the environment or in "
                    + Path.of(".env").toAbsolutePath() + ")");
        }
        return value;
    }

    /** The 8-digit per-run suffix. */
    public String suffix() {
        return suffix;
    }

    // ------------------------------------------------------------------------------- URLs (9.3)

    public String serviceUrl() {
        return SERVICE_URL;
    }

    /** The stand-in, {@code GATEWAY_BASE_URL} (host-run value {@code http://localhost:8090}). */
    public String stubUrl() {
        return value("GATEWAY_BASE_URL");
    }

    public String mailpitUrl() {
        return MAILPIT_URL;
    }

    /** {@code KEYCLOAK_SERVER_URL} (host-run value {@code http://localhost:8180/auth}). */
    public String keycloakUrl() {
        return value("KEYCLOAK_SERVER_URL");
    }

    public String realm() {
        return value("KEYCLOAK_REALM");
    }

    public String tokenEndpoint() {
        return keycloakUrl() + "/realms/" + realm() + "/protocol/openid-connect/token";
    }

    // -------------------------------------------------------------------------- created names

    /** {@code E2E_<SCENARIO>_<suffix>}, for example {@code E2E_S3_12345678}. */
    public String roleName(String scenario) {
        return "E2E_" + scenario.toUpperCase(Locale.ROOT) + "_" + suffix;
    }

    /** {@code e2e-<scenario>-<suffix>}. */
    public String username(String scenario) {
        return "e2e-" + scenario.toLowerCase(Locale.ROOT) + "-" + suffix;
    }

    /** {@code e2e-<scenario>-<suffix>@cce.local}. */
    public String email(String scenario) {
        return username(scenario) + "@cce.local";
    }

    /** {@code e2e-<scenario>-<suffix>}. */
    public String menuKey(String scenario) {
        return "e2e-" + scenario.toLowerCase(Locale.ROOT) + "-" + suffix;
    }
}
