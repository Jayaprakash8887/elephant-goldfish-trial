package org.cce.gatewaystub;

import java.util.Set;

/** The distinct {@code permission_name} values of {@code api_permissions}. */
public interface PermissionStore {

    Set<String> distinctPermissionNames();
}
