package org.cce.admin.client;

/**
 * Calls gateway-service's {@code POST /api/permissions/sync-to-keycloak} (section 7, GW-1). Never
 * throws: every failure is a {@link SyncResult} with {@code success = false}. Service tests replace
 * this interface with a mock.
 */
public interface GatewaySyncClient {

    SyncResult sync();
}
