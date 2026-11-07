package com.mouradzouabi.ldapclient.core

import com.unboundid.ldap.sdk.Entry
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType
import com.unboundid.util.StaticUtils
import java.time.Duration
import java.time.Instant

enum class AccountFlavor(private val label: String) {
    ACTIVE_DIRECTORY("Active Directory"),
    PPOLICY("Password policy (OpenLDAP ppolicy)"),
    DS389("389 Directory Server / Red Hat DS"),
    UNKNOWN("Unknown");

    override fun toString() = label
}

data class AccountFact(val label: String, val value: String, val warning: Boolean = false)

data class AccountStatus(
    val flavor: AccountFlavor,
    val disabled: Boolean,
    val locked: Boolean,
    val passwordExpired: Boolean,
    val mustChangePassword: Boolean,
    val accountExpired: Boolean,
    val facts: List<AccountFact>,
) {
    val summary: String
        get() = listOfNotNull(
            "disabled".takeIf { disabled },
            "locked".takeIf { locked },
            "password expired".takeIf { passwordExpired },
            "must change password".takeIf { mustChangePassword },
            "account expired".takeIf { accountExpired },
        ).ifEmpty { listOf("active") }.joinToString(", ").replaceFirstChar { it.uppercase() }
}

/** Reads and changes the lock / enable / password-expiry state of user accounts on the common directory servers. */
object AccountTools {
    const val RELAX_RULES_OID = "1.3.6.1.4.1.4203.666.5.12"

    /** Attributes needed by [analyze]; most of them are operational and must be requested by name. */
    val ATTRIBUTES = listOf(
        "*", "objectClass",
        "userAccountControl", "msDS-User-Account-Control-Computed", "lockoutTime", "pwdLastSet", "accountExpires",
        "badPwdCount", "lastLogonTimestamp",
        "pwdAccountLockedTime", "pwdFailureTime", "pwdChangedTime", "pwdReset", "pwdPolicySubentry", "pwdGraceUseTime",
        "nsAccountLock", "passwordRetryCount", "accountUnlockTime", "passwordExpirationTime",
    )

    /** Attributes of the AD domain object / ppolicy entry used to compute expirations. */
    val POLICY_ATTRIBUTES = listOf("maxPwdAge", "lockoutDuration", "pwdMaxAge", "pwdLockoutDuration", "pwdLockout")

    const val PPOLICY_PERMANENT_LOCK = "000001010000Z"
    private const val DS389_MUST_CHANGE = "19700101000000Z"

    private const val UAC_DISABLED = 0x2
    private const val UAC_LOCKOUT = 0x10
    private const val UAC_PASSWORD_NEVER_EXPIRES = 0x10000
    private const val UAC_PASSWORD_EXPIRED = 0x800000

    private val ACCOUNT_CLASSES = setOf(
        "person", "organizationalperson", "inetorgperson", "user", "computer", "posixaccount", "account", "shadowaccount",
    )

    /** True when the object classes describe something that can log in. */
    fun isAccount(objectClasses: Collection<String>) = objectClasses.any { it.lowercase() in ACCOUNT_CLASSES }

    fun flavor(entry: Entry, vendorName: String? = null): AccountFlavor {
        val vendor = vendorName.orEmpty().lowercase()
        return when {
            entry.hasAttribute("userAccountControl") || entry.hasAttribute("sAMAccountName") -> AccountFlavor.ACTIVE_DIRECTORY
            entry.hasAttribute("nsAccountLock") || entry.hasAttribute("passwordRetryCount") ||
                entry.hasAttribute("passwordExpirationTime") || "389" in vendor || "red hat" in vendor || "fedora" in vendor ->
                AccountFlavor.DS389
            PPOLICY_ATTRIBUTES.any { entry.hasAttribute(it) } || "openldap" in vendor -> AccountFlavor.PPOLICY
            else -> AccountFlavor.UNKNOWN
        }
    }

    /** DN of the entry holding the policy that applies to [entry], or null when unknown. */
    fun policyDn(entry: Entry, flavor: AccountFlavor, defaultNamingContext: String?): String? = when (flavor) {
        AccountFlavor.ACTIVE_DIRECTORY -> defaultNamingContext?.takeIf { it.isNotBlank() }
        AccountFlavor.PPOLICY -> entry.getAttributeValue("pwdPolicySubentry")
        else -> null
    }

    fun analyze(entry: Entry, policy: Entry?, flavor: AccountFlavor, now: Instant = Instant.now()): AccountStatus = when (flavor) {
        AccountFlavor.ACTIVE_DIRECTORY -> analyzeAd(entry, policy, now)
        AccountFlavor.PPOLICY, AccountFlavor.UNKNOWN -> analyzePpolicy(entry, policy, now, flavor)
        AccountFlavor.DS389 -> analyze389(entry, now)
    }

    private fun analyzeAd(entry: Entry, policy: Entry?, now: Instant): AccountStatus {
        val facts = mutableListOf<AccountFact>()
        val uac = entry.getAttributeValueAsInteger("userAccountControl") ?: 0
        val computed = entry.getAttributeValueAsInteger("msDS-User-Account-Control-Computed")
        val disabled = uac and UAC_DISABLED != 0
        facts += AccountFact("Account", if (disabled) "Disabled" else "Enabled", disabled)

        val lockoutTime = fileTime(entry.getAttributeValueAsLong("lockoutTime"))
        val lockoutDuration = adInterval(policy?.getAttributeValueAsLong("lockoutDuration"))
        val locked = when {
            computed != null -> computed and UAC_LOCKOUT != 0
            lockoutTime == null -> uac and UAC_LOCKOUT != 0
            lockoutDuration == null -> true
            else -> lockoutTime.plus(lockoutDuration).isAfter(now)
        }
        facts += AccountFact("Locked out", if (locked) "Yes" else "No", locked)
        lockoutTime?.let { facts += AccountFact("Lockout time", format(it), locked) }
        if (locked && lockoutTime != null && lockoutDuration != null) facts += AccountFact("Unlocks at", format(lockoutTime.plus(lockoutDuration)))
        entry.getAttributeValue("badPwdCount")?.let { facts += AccountFact("Bad password count", it) }

        val pwdLastSetRaw = entry.getAttributeValueAsLong("pwdLastSet")
        val mustChange = pwdLastSetRaw == 0L
        val pwdLastSet = fileTime(pwdLastSetRaw)
        facts += AccountFact("Password last set", when {
            mustChange -> "Never (must change at next logon)"
            pwdLastSet != null -> format(pwdLastSet)
            else -> "Unknown"
        }, mustChange)
        val neverExpires = uac and UAC_PASSWORD_NEVER_EXPIRES != 0
        val maxAge = adInterval(policy?.getAttributeValueAsLong("maxPwdAge"))
        val expiresAt = if (!neverExpires && pwdLastSet != null && maxAge != null) pwdLastSet.plus(maxAge) else null
        val passwordExpired = computed?.let { it and UAC_PASSWORD_EXPIRED != 0 } ?: (expiresAt?.isBefore(now) ?: false)
        facts += AccountFact("Password expires", when {
            neverExpires -> "Never (password never expires)"
            expiresAt != null -> format(expiresAt)
            else -> "Unknown"
        }, passwordExpired)

        val accountExpires = fileTime(entry.getAttributeValueAsLong("accountExpires"))
        val accountExpired = accountExpires?.isBefore(now) ?: false
        facts += AccountFact("Account expires", accountExpires?.let(::format) ?: "Never", accountExpired)
        fileTime(entry.getAttributeValueAsLong("lastLogonTimestamp"))?.let { facts += AccountFact("Last logon (approx.)", format(it)) }
        return AccountStatus(AccountFlavor.ACTIVE_DIRECTORY, disabled, locked, passwordExpired, mustChange, accountExpired, facts)
    }

    private fun analyzePpolicy(entry: Entry, policy: Entry?, now: Instant, flavor: AccountFlavor): AccountStatus {
        val facts = mutableListOf<AccountFact>()
        val lockedValue = entry.getAttributeValue("pwdAccountLockedTime")
        val disabled = lockedValue == PPOLICY_PERMANENT_LOCK
        val lockedAt = lockedValue?.takeUnless { disabled }?.let(::generalizedTime)
        val lockoutDuration = policy?.getAttributeValueAsLong("pwdLockoutDuration")?.takeIf { it > 0 }?.let(Duration::ofSeconds)
        val locked = !disabled && lockedValue != null &&
            (lockedAt == null || lockoutDuration == null || lockedAt.plus(lockoutDuration).isAfter(now))
        facts += AccountFact("Account", if (disabled) "Disabled (locked until an administrator unlocks it)" else "Enabled", disabled)
        facts += AccountFact("Locked out", if (locked) "Yes" else "No", locked)
        lockedAt?.let { facts += AccountFact("Locked since", format(it), locked) }
        if (locked && lockedAt != null && lockoutDuration != null) facts += AccountFact("Unlocks at", format(lockedAt.plus(lockoutDuration)))
        val failures = entry.getAttributeValues("pwdFailureTime")?.size ?: 0
        if (failures > 0) facts += AccountFact("Recent failed binds", failures.toString(), true)

        val changedAt = entry.getAttributeValue("pwdChangedTime")?.let(::generalizedTime)
        facts += AccountFact("Password changed", changedAt?.let(::format) ?: "Unknown")
        val maxAge = policy?.getAttributeValueAsLong("pwdMaxAge")?.takeIf { it > 0 }?.let(Duration::ofSeconds)
        val expiresAt = if (changedAt != null && maxAge != null) changedAt.plus(maxAge) else null
        val passwordExpired = expiresAt?.isBefore(now) ?: false
        facts += AccountFact("Password expires", expiresAt?.let(::format) ?: if (policy != null && maxAge == null) "Never" else "Unknown", passwordExpired)
        val mustChange = entry.getAttributeValue("pwdReset").equals("TRUE", ignoreCase = true)
        facts += AccountFact("Must change password", if (mustChange) "Yes" else "No", mustChange)
        entry.getAttributeValue("pwdPolicySubentry")?.let { facts += AccountFact("Policy", it) }
        return AccountStatus(flavor, disabled, locked, passwordExpired, mustChange, false, facts)
    }

    private fun analyze389(entry: Entry, now: Instant): AccountStatus {
        val facts = mutableListOf<AccountFact>()
        val disabled = entry.getAttributeValue("nsAccountLock").equals("TRUE", ignoreCase = true)
        facts += AccountFact("Account", if (disabled) "Disabled (nsAccountLock)" else "Enabled", disabled)
        val unlockAt = entry.getAttributeValue("accountUnlockTime")?.let(::generalizedTime)
        val locked = unlockAt?.isAfter(now) ?: false
        facts += AccountFact("Locked out", if (locked) "Yes" else "No", locked)
        if (locked) facts += AccountFact("Unlocks at", format(unlockAt!!))
        entry.getAttributeValue("passwordRetryCount")?.let { facts += AccountFact("Failed binds", it, it != "0") }
        val expirationRaw = entry.getAttributeValue("passwordExpirationTime")
        val mustChange = expirationRaw == DS389_MUST_CHANGE
        val expiresAt = expirationRaw?.takeUnless { mustChange }?.let(::generalizedTime)
        val passwordExpired = expiresAt?.isBefore(now) ?: false
        facts += AccountFact("Password expires", when {
            mustChange -> "Must change at next login"
            expiresAt != null -> format(expiresAt)
            else -> "Never / unknown"
        }, passwordExpired || mustChange)
        return AccountStatus(AccountFlavor.DS389, disabled, locked, passwordExpired, mustChange, false, facts)
    }

    fun unlock(entry: Entry, flavor: AccountFlavor): List<Modification> = when (flavor) {
        AccountFlavor.ACTIVE_DIRECTORY -> listOf(Modification(ModificationType.REPLACE, "lockoutTime", "0"))
        AccountFlavor.PPOLICY, AccountFlavor.UNKNOWN -> listOfNotNull(
            Modification(ModificationType.DELETE, "pwdAccountLockedTime").takeIf { entry.hasAttribute("pwdAccountLockedTime") },
            Modification(ModificationType.DELETE, "pwdFailureTime").takeIf { entry.hasAttribute("pwdFailureTime") },
        )
        AccountFlavor.DS389 -> listOfNotNull(
            Modification(ModificationType.REPLACE, "passwordRetryCount", "0"),
            Modification(ModificationType.DELETE, "accountUnlockTime").takeIf { entry.hasAttribute("accountUnlockTime") },
        )
    }

    fun setDisabled(entry: Entry, flavor: AccountFlavor, disabled: Boolean): List<Modification> = when (flavor) {
        AccountFlavor.ACTIVE_DIRECTORY -> {
            val uac = entry.getAttributeValueAsInteger("userAccountControl") ?: 0x200
            val updated = if (disabled) uac or UAC_DISABLED else uac and UAC_DISABLED.inv()
            listOf(Modification(ModificationType.REPLACE, "userAccountControl", updated.toString()))
        }
        AccountFlavor.PPOLICY, AccountFlavor.UNKNOWN ->
            if (disabled) listOf(Modification(ModificationType.REPLACE, "pwdAccountLockedTime", PPOLICY_PERMANENT_LOCK))
            else listOfNotNull(Modification(ModificationType.DELETE, "pwdAccountLockedTime").takeIf { entry.hasAttribute("pwdAccountLockedTime") })
        AccountFlavor.DS389 ->
            if (disabled) listOf(Modification(ModificationType.REPLACE, "nsAccountLock", "TRUE"))
            else listOfNotNull(Modification(ModificationType.DELETE, "nsAccountLock").takeIf { entry.hasAttribute("nsAccountLock") })
    }

    fun forcePasswordChange(flavor: AccountFlavor): List<Modification> = when (flavor) {
        AccountFlavor.ACTIVE_DIRECTORY -> listOf(Modification(ModificationType.REPLACE, "pwdLastSet", "0"))
        AccountFlavor.PPOLICY, AccountFlavor.UNKNOWN -> listOf(Modification(ModificationType.REPLACE, "pwdReset", "TRUE"))
        AccountFlavor.DS389 -> listOf(Modification(ModificationType.REPLACE, "passwordExpirationTime", DS389_MUST_CHANGE))
    }

    /** Windows FILETIME (100 ns ticks since 1601); 0 and the maximum value mean "never". */
    fun fileTime(value: Long?): Instant? {
        if (value == null || value <= 0L || value == Long.MAX_VALUE) return null
        return FILETIME_EPOCH.plusSeconds(value / 10_000_000).plusNanos(value % 10_000_000 * 100)
    }

    /** AD stores durations as negative 100 ns intervals; the minimum value means "forever". */
    private fun adInterval(value: Long?): Duration? {
        if (value == null || value == 0L || value == Long.MIN_VALUE) return null
        val ticks = if (value < 0) -value else value
        return Duration.ofSeconds(ticks / 10_000_000, ticks % 10_000_000 * 100)
    }

    private fun generalizedTime(value: String): Instant? =
        runCatching { StaticUtils.decodeGeneralizedTime(value).toInstant() }.getOrNull()

    private fun format(instant: Instant): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(instant)

    private val FILETIME_EPOCH: Instant = Instant.parse("1601-01-01T00:00:00Z")
    private val PPOLICY_ATTRIBUTES = listOf("pwdAccountLockedTime", "pwdFailureTime", "pwdChangedTime", "pwdReset", "pwdPolicySubentry")
}
