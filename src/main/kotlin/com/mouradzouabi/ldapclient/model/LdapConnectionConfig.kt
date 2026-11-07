package com.mouradzouabi.ldapclient.model

import java.util.UUID

enum class SecurityMode(private val displayName: String) {
    NONE("None"),
    LDAPS("LDAPS (SSL/TLS)"),
    START_TLS("StartTLS");

    override fun toString() = displayName
}

enum class AuthMethod(private val displayName: String) {
    ANONYMOUS("Anonymous"),
    SIMPLE("Simple bind");

    override fun toString() = displayName
}

/**
 * Persistent connection definition. Passwords are never stored here, see [LdapCredentials].
 */
class LdapConnectionConfig {
    var id: String = UUID.randomUUID().toString()
    var name: String = ""
    var host: String = "localhost"
    var port: Int = 389
    var security: SecurityMode = SecurityMode.NONE
    var trustAllCertificates: Boolean = false
    var verifyHostname: Boolean = true
    var authMethod: AuthMethod = AuthMethod.SIMPLE
    var bindDn: String = ""
    /** Optional base DN(s), separated by `|`. When empty, the server naming contexts are used. */
    var baseDn: String = ""
    var pageSize: Int = 200
    var connectTimeoutSeconds: Int = 10
    var responseTimeoutSeconds: Int = 60
    var maxConnections: Int = 5
    var readOnly: Boolean = false
    var followReferrals: Boolean = false

    val displayName: String
        get() = name.ifBlank { "$host:$port" }

    val baseDnList: List<String>
        get() = baseDn.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    fun copy(): LdapConnectionConfig = LdapConnectionConfig().also { it.copyFrom(this) }

    fun copyFrom(other: LdapConnectionConfig) {
        id = other.id
        name = other.name
        host = other.host
        port = other.port
        security = other.security
        trustAllCertificates = other.trustAllCertificates
        verifyHostname = other.verifyHostname
        authMethod = other.authMethod
        bindDn = other.bindDn
        baseDn = other.baseDn
        pageSize = other.pageSize
        connectTimeoutSeconds = other.connectTimeoutSeconds
        responseTimeoutSeconds = other.responseTimeoutSeconds
        maxConnections = other.maxConnections
        readOnly = other.readOnly
        followReferrals = other.followReferrals
    }

    /** True when a change requires the existing connection pool to be re-created. */
    fun connectionSettingsDiffer(other: LdapConnectionConfig): Boolean =
        host != other.host || port != other.port || security != other.security ||
            trustAllCertificates != other.trustAllCertificates || verifyHostname != other.verifyHostname ||
            authMethod != other.authMethod || bindDn != other.bindDn || baseDn != other.baseDn ||
            pageSize != other.pageSize || connectTimeoutSeconds != other.connectTimeoutSeconds ||
            responseTimeoutSeconds != other.responseTimeoutSeconds || maxConnections != other.maxConnections ||
            readOnly != other.readOnly || followReferrals != other.followReferrals
}
