package com.incidentplatform.domain.enums;

/**
 * Incident Severity — How bad is this incident?
 *
 * WHY an enum?
 * Enums enforce a closed set of valid values at compile time.
 * If we used String, a typo ("CRITCAL") would silently corrupt data.
 * Stored in DB as String (not ordinal) — see @Enumerated(EnumType.STRING) in entity.
 *
 * WHY not store as integer ordinal?
 * If you reorder enum values, all DB records corrupt. String = safe refactoring.
 *
 * SEV levels are industry standard (Google, PagerDuty, Atlassian Statuspage):
 * SEV1 = Total outage (all hands on deck)
 * SEV2 = Major degradation (senior engineers paged)
 * SEV3 = Partial degradation (team investigates during business hours)
 * SEV4 = Minor issue (ticket created, fixed in next sprint)
 */
public enum Severity {
    SEV1,   // Critical — complete service outage
    SEV2,   // High — major feature degradation
    SEV3,   // Medium — partial degradation
    SEV4    // Low — minor issue, no immediate impact
}
