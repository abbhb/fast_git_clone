package com.tencent.bk.devops.atom.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitCredentialStoreTest {
    @Test
    fun `normalizes host from raw host http url and credential url`() {
        assertEquals("code.cwoa.net", GitCredentialStore.normalizeHost("code.cwoa.net"))
        assertEquals("code.cwoa.net", GitCredentialStore.normalizeHost("https://code.cwoa.net/group/repo.git"))
        assertEquals("code.cwoa.net", GitCredentialStore.normalizeHost("https://oauth2:token@CODE.CWOA.NET/group/repo.git"))
        assertEquals("code.cwoa.net:8443", GitCredentialStore.normalizeHost("https://oauth2:token@code.cwoa.net:8443/group/repo.git"))
    }

    @Test
    fun `matches credential host by parsing url host`() {
        assertTrue(GitCredentialStore.credentialHostMatches("https://oauth2:token@code.cwoa.net", "code.cwoa.net"))
        assertTrue(GitCredentialStore.credentialHostMatches("https://oauth2:token@code.cwoa.net/group/repo.git", "code.cwoa.net"))
        assertFalse(GitCredentialStore.credentialHostMatches("https://oauth2:token@git.example.com", "code.cwoa.net"))
    }

    @Test
    fun `replaces credentials for same host and keeps other hosts`() {
        val updatedLines = GitCredentialStore.updatedCredentialLines(
            existingLines = sequenceOf(
                "https://old:bad@code.cwoa.net",
                "https://oauth2:wrong@code.cwoa.net/group/repo.git",
                "https://keep:token@git.example.com",
                "",
                "not-a-url",
            ),
            normalizedHost = "code.cwoa.net",
            credentialLine = "https://oauth2:new-token@code.cwoa.net",
        )

        assertEquals(
            listOf(
                "https://keep:token@git.example.com",
                "https://oauth2:new-token@code.cwoa.net",
            ),
            updatedLines,
        )
    }

    @Test
    fun `credential line encodes username and token`() {
        assertEquals(
            "https://user%40corp:tok%23en@code.cwoa.net",
            GitCredentialStore.credentialLine("user@corp", "tok#en", "code.cwoa.net"),
        )
    }
}