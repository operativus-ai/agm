package com.operativus.agentmanager.control.service.erasure;

/**
 * Strategy interface for per-domain GDPR Article 17 erasure handlers.
 * Each implementation erases all data attributable to the given userId
 * within its domain and returns a count of affected records.
 */
public interface ErasureHandler {

    /** Human-readable domain name used in summary maps (e.g. "sessions"). */
    String domain();

    /**
     * Erase all data for the given userId within this handler's domain.
     * @return number of records erased or anonymized
     */
    int erase(String userId);
}
