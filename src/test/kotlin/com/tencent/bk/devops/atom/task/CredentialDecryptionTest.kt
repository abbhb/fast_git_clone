package com.tencent.bk.devops.atom.task

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialDecryptionTest {
    private val encoder: Base64.Encoder = Base64.getEncoder()

    @Test
    fun `requests padded key derivation from the credential service`() {
        assertEquals(
            "/ticket/api/build/credentials/e-123?publicKey=PUB&padding=true",
            buildCredentialRequestPath("e-123", "PUB"),
        )
    }

    @Test
    fun `decrypts credentials encrypted with the padded derivation`() {
        repeat(DECRYPT_ATTEMPTS) { index ->
            val clientPair = DH.initKey()
            val serverPair = DH.initKey()
            val secret = "glpat-padded-$index"
            val encrypted = DH.encrypt(secret.toByteArray(StandardCharsets.UTF_8), clientPair.publicKey, serverPair.privateKey)

            assertEquals(
                secret,
                decryptCredentialValue(
                    encoder.encodeToString(encrypted),
                    encoder.encodeToString(serverPair.publicKey),
                    clientPair.privateKey,
                ),
            )
        }
    }

    @Test
    fun `falls back to the legacy derivation when the service does not pad`() {
        var covered = false
        repeat(LEGACY_SEARCH_ATTEMPTS) { index ->
            if (covered) {
                return@repeat
            }
            val clientPair = DH.initKey()
            val serverPair = DH.initKey()
            val secret = "glpat-legacy-$index"
            val encrypted = DH.encrypt(
                secret.toByteArray(StandardCharsets.UTF_8),
                clientPair.publicKey,
                serverPair.privateKey,
                legacy = true,
            )
            // 只有共享密钥最高字节为 0（约 4% 概率）时两种派生结果才不同，那种情况下才需要回退
            if (runCatching { DH.decrypt(encrypted, serverPair.publicKey, clientPair.privateKey) }.isSuccess) {
                return@repeat
            }

            assertEquals(
                secret,
                decryptCredentialValue(
                    encoder.encodeToString(encrypted),
                    encoder.encodeToString(serverPair.publicKey),
                    clientPair.privateKey,
                ),
            )
            covered = true
        }
        assertTrue(covered, "未能在 $LEGACY_SEARCH_ATTEMPTS 次尝试内构造出填充与不填充派生不一致的密钥对")
    }

    @Test
    fun `fails loudly instead of using undecrypted credential values`() {
        val clientPair = DH.initKey()
        val serverPair = DH.initKey()
        val notDecryptable = encoder.encodeToString(ByteArray(5) { 7 })

        assertFailsWith<PluginException> {
            decryptCredentialValue(notDecryptable, encoder.encodeToString(serverPair.publicKey), clientPair.privateKey)
        }
    }

    @Test
    fun `keeps plaintext values when the service returns no public key`() {
        assertEquals("plain-secret", decryptCredentialValue("plain-secret", "", ByteArray(0)))
    }

    @Test
    fun `tells authentication failures apart from missing branches`() {
        assertTrue(
            isAuthenticationFailure(
                "remote: HTTP Basic: Access denied. If a password was provided for Git authentication, ...",
            ),
        )
        assertTrue(isAuthenticationFailure("fatal: Authentication failed for 'https://code.cwoa.net/a/b.git/'"))
        assertTrue(
            isAuthenticationFailure(
                "fatal: could not read Username for 'https://code.cwoa.net': terminal prompts disabled",
            ),
        )
        assertFalse(isAuthenticationFailure("fatal: couldn't find remote ref refs/heads/dev/5.3.0/foo"))
    }

    companion object {
        private const val DECRYPT_ATTEMPTS = 20
        private const val LEGACY_SEARCH_ATTEMPTS = 2000
    }
}
