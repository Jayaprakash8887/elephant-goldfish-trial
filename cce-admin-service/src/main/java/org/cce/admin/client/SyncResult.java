package org.cce.admin.client;

/**
 * The outcome of one gateway sync call (section 7). {@code httpStatus} is null when no response
 * arrived. {@code errorSummary} is null on success.
 */
public record SyncResult(boolean success, Integer httpStatus, String errorSummary) {
}
