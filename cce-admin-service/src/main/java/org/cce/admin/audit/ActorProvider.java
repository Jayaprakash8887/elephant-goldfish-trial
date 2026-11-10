package org.cce.admin.audit;

import java.util.UUID;
import org.cce.admin.security.CurrentUser;
import org.springframework.stereotype.Component;

/**
 * The actor of an audit row (section 8): the request's {@link CurrentUser}, or the
 * {@code system:bootstrap} actor when there is no request caller. Service tests that call services
 * directly therefore record {@code system:bootstrap}.
 */
@Component
public class ActorProvider {

    public static final String SYSTEM_BOOTSTRAP = "system:bootstrap";

    /** {@code userId} and {@code keycloakId} are null for {@code system:bootstrap}. */
    public record Actor(UUID userId, String keycloakId, String username) {
    }

    public Actor current() {
        return CurrentUser.current()
                .map(user -> new Actor(user.id(), user.keycloakId().toString(), user.username()))
                .orElseGet(() -> new Actor(null, null, SYSTEM_BOOTSTRAP));
    }
}
