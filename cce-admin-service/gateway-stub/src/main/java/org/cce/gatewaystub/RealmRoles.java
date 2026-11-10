package org.cce.gatewaystub;

import java.util.List;
import java.util.Map;

/** Lists (with attributes), creates and deletes realm roles. */
public interface RealmRoles {

    /** The attribute that marks a realm role this stand-in created (GW-4). */
    String MANAGED_ATTRIBUTE = "cce-managed";

    /** A realm role with its attributes. */
    record RealmRole(String name, Map<String, List<String>> attributes) {

        public RealmRole {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        /** Whether the role carries {@code cce-managed=true}. */
        public boolean managed() {
            List<String> values = attributes.get(MANAGED_ATTRIBUTE);
            return values != null && values.contains("true");
        }
    }

    List<RealmRole> list();

    /** Creates the role with the attribute {@code cce-managed=true}. */
    void create(String name);

    void delete(String name);
}
