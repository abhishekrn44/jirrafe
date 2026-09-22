package io.jirrafe.frameworks.spring

/**
 * What a `SecurityFilterChain` says about a path: `requestMatchers(<pattern>).hasRole("ADMIN")` read from the
 * source, in declaration order, the first matching pattern deciding as Spring does. A route carries its rule
 * as its signature, so "admin" finds every admin endpoint, not only the ones annotated @PreAuthorize.
 * (No literal patterns in this comment: a slash-star inside a KDoc opens a nested comment.)
 */
internal object RouteAccess {
    private val MATCHER = Regex("""(?:requestMatchers|antMatchers|mvcMatchers|securityMatcher)\s*\(([^)]*)\)\s*\.\s*(permitAll|authenticated|denyAll|anonymous|fullyAuthenticated|rememberMe|hasRole|hasAnyRole|hasAuthority|hasAnyAuthority|access)\s*\(([^)]*)\)""")
    private val ANY = Regex("""anyRequest\s*\(\s*\)\s*\.\s*(permitAll|authenticated|denyAll|anonymous|fullyAuthenticated|hasRole|hasAnyRole|hasAuthority|hasAnyAuthority|access)\s*\(([^)]*)\)""")
    private val QUOTED = Regex("\"([^\"]+)\"")

    /** `pattern -> rule` pairs in declaration order, `hasRole(ADMIN)` for an admin prefix; an `anyRequest()` rule last, as the match-all pattern. */
    fun rules(source: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (m in MATCHER.findAll(source)) for (p in QUOTED.findAll(m.groupValues[1])) out += p.groupValues[1] to rule(m.groupValues[2], m.groupValues[3])
        ANY.find(source)?.let { m -> out += "/**" to rule(m.groupValues[1], m.groupValues[2]) }
        return out
    }

    private fun rule(name: String, args: String) = name + (args.trim().takeIf { it.isNotEmpty() }?.let { "(" + it.replace("\"", "").replace("'", "") + ")" } ?: "")

    /** Ant-style: `**` any depth, `*` within a segment, `{var}` one segment. */
    fun matches(pattern: String, path: String): Boolean {
        val re = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when {
                pattern.startsWith("**", i) -> { re.append(".*"); i += 2; continue }
                pattern[i] == '*' -> re.append("[^/]*")
                pattern[i] == '{' -> { re.append("[^/]+"); i = pattern.indexOf('}', i).let { if (it < 0) pattern.length - 1 else it } }
                else -> re.append(Regex.escape(pattern[i].toString()))
            }
            i++
        }
        return Regex(re.append('$').toString()).matches(path)
    }
}
