package com.guicedee.service.registry;

/**
 * Health status of a registered service.
 */
public enum ServiceStatus
{
    /** Service is healthy and responding. */
    UP,
    /** Service is not responding or returned error. */
    DOWN,
    /** Service health has not been checked yet. */
    UNKNOWN,
    /** Service is responding but with degraded performance. */
    DEGRADED,
    /** Service is reachable but health response is not valid (e.g. HTML instead of JSON). */
    WARNING
}

