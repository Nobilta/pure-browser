package com.mybrowser.filter

import java.util.Locale

internal enum class UserRuleGroupKind { ALL_SITES, SITE, UNKNOWN }

/** One site's hand-written rules; [site] is null for the generic and unknown buckets. */
internal data class UserRuleGroup(
    val kind: UserRuleGroupKind,
    val site: String?,
    val rules: List<String>,
) {
    val key: String get() = when (kind) {
        UserRuleGroupKind.ALL_SITES -> "all"
        UserRuleGroupKind.SITE -> "site:$site"
        UserRuleGroupKind.UNKNOWN -> "unknown"
    }
}

/**
 * Reads a hand-written filter rule far enough to group it by the site it applies to.
 *
 * The rules are still stored as plain strings; this is only a presentation layer. Anything the
 * parser does not recognise goes to [UserRuleGroupKind.UNKNOWN] rather than being dropped, so the
 * original text stays visible and removable.
 */
internal object UserRuleGrouping {
    internal data class Scope(val kind: UserRuleGroupKind, val site: String?)

    fun group(rules: List<String>): List<UserRuleGroup> =
        rules.groupBy(::scopeOf).entries
            .sortedWith(compareBy({ it.key.kind }, { it.key.site }))
            .map { (scope, entries) -> UserRuleGroup(scope.kind, scope.site, entries) }

    fun scopeOf(rule: String): Scope {
        val line = rule.trim()
        val cosmetic = line.split("##", "#@#", limit = 2)
        if (cosmetic.size == 2) {
            if (!BlockRules.isValid(line)) return Scope(UserRuleGroupKind.UNKNOWN, null)
            val site = cosmetic.first()
            return when {
                site.isEmpty() -> Scope(UserRuleGroupKind.ALL_SITES, null)
                site != site.trim() -> Scope(UserRuleGroupKind.UNKNOWN, null)
                else -> BlockRules.siteHost(site)?.let { Scope(UserRuleGroupKind.SITE, it) }
                    ?: Scope(UserRuleGroupKind.UNKNOWN, null)
            }
        }
        val body = line.removePrefix("@@")
        val address = body.removePrefix("||").removePrefix("|")
        val authority = when {
            address.startsWith("http://", ignoreCase = true) || address.startsWith("https://", ignoreCase = true) ->
                address.substringAfter("://")
            body.startsWith("||") -> address
            else -> return Scope(UserRuleGroupKind.UNKNOWN, null)
        }.takeWhile { it !in "/^$|?#" }
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)
        return if (host.isNotEmpty() && !host.startsWith('.') && !host.endsWith('.') &&
            host.all { it in 'a'..'z' || it in '0'..'9' || it in "_.-" }
        ) Scope(UserRuleGroupKind.SITE, host) else Scope(UserRuleGroupKind.UNKNOWN, null)
    }
}
