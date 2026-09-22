package io.jirrafe.frameworks.spring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteAccessTest {
    private val chain = """
        http.authorizeHttpRequests((authorize) -> authorize.requestMatchers("/v1/login/**").permitAll()
            .requestMatchers("/v1/auth/**", "/h2/**").permitAll()
            .requestMatchers("/v1/user/**").hasRole("ADMIN")
            .requestMatchers("/v1/report/**").hasAnyRole("ADMIN", "AUDIT")
            .anyRequest().authenticated())
    """

    @Test
    fun `rules in declaration order, anyRequest last`() {
        assertEquals(
            listOf("/v1/login/**" to "permitAll", "/v1/auth/**" to "permitAll", "/h2/**" to "permitAll", "/v1/user/**" to "hasRole(ADMIN)", "/v1/report/**" to "hasAnyRole(ADMIN, AUDIT)", "/**" to "authenticated"),
            RouteAccess.rules(chain),
        )
    }

    @Test
    fun `ant patterns match a route's path`() {
        assertTrue(RouteAccess.matches("/v1/user/**", "/v1/user/approveRequest/{tempFk}"))
        assertTrue(RouteAccess.matches("/api/owners/{id}/pets", "/api/owners/{ownerId}/pets"))
        assertTrue(RouteAccess.matches("/api/*/list", "/api/vets/list"))
        assertFalse(RouteAccess.matches("/v1/auth/**", "/v1/user/saveRequest"))
        assertFalse(RouteAccess.matches("/api/*/list", "/api/a/b/list"))
    }
}
