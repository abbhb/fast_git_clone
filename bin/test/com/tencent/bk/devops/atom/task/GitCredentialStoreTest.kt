package com.tencent.bk.devops.atom.task

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitCredentialHelperTest {
    @Test
    fun `normalizes host from raw host http url and credential url`() {
        assertEquals("code.cwoa.net", GitCredentialConfig.normalizeHost("code.cwoa.net"))
        assertEquals("code.cwoa.net", GitCredentialConfig.normalizeHost("https://code.cwoa.net/group/repo.git"))
        assertEquals("code.cwoa.net", GitCredentialConfig.normalizeHost("https://oauth2:token@CODE.CWOA.NET/group/repo.git"))
        assertEquals("code.cwoa.net:8443", GitCredentialConfig.normalizeHost("https://oauth2:token@code.cwoa.net:8443/group/repo.git"))
    }

    @Test
    fun `creates helper environment without embedding credentials`() {
        val helperCommand = GitCredentialConfig.helperCommand(
            taskId = "task-1",
            jarPath = Paths.get("/tmp/fast git clone.jar"),
            javaPath = Paths.get("/usr/bin/java"),
        )
        val environment = GitCredentialConfig.environment(
            helperCommand = helperCommand,
            taskId = "task-1",
            supportsEmptyCredentialHelper = true,
        )

        assertEquals("3", environment["GIT_CONFIG_COUNT"])
        assertEquals("credential.helper", environment["GIT_CONFIG_KEY_0"])
        assertEquals("", environment["GIT_CONFIG_VALUE_0"])
        assertEquals(helperCommand, environment["GIT_CONFIG_VALUE_1"])
        assertEquals("credential.useHttpPath", environment["GIT_CONFIG_KEY_2"])
        assertFalse(helperCommand.contains("token"))
        assertFalse(helperCommand.contains("password"))
    }

    @Test
    fun `global fast helper is active only after the last reset helper`() {
        val fastHelper = "!/usr/bin/java -cp /tmp/fast_git_clone.jar ${FastGitCredentialHelper::class.java.name}"

        assertFalse(GitCredentialConfig.hasActiveFastHelper(listOf("store", fastHelper, ""), true))
        assertTrue(GitCredentialConfig.hasActiveFastHelper(listOf("store", "", fastHelper), true))
        assertTrue(GitCredentialConfig.hasActiveFastHelper(listOf("store", fastHelper), false))
        assertFalse(GitCredentialConfig.hasActiveFastHelper(listOf("store", "cache"), false))
    }

    @Test
    fun `resets global helpers on old git when plaintext store may be active`() {
        val fastHelper = "!/usr/bin/java -cp /tmp/fast_git_clone.jar ${FastGitCredentialHelper::class.java.name}"

        assertTrue(GitCredentialConfig.shouldResetGlobalCredentialHelpers(listOf("store"), false))
        assertTrue(GitCredentialConfig.shouldResetGlobalCredentialHelpers(listOf("cache", fastHelper), false))
        assertFalse(GitCredentialConfig.shouldResetGlobalCredentialHelpers(listOf(fastHelper), false))
        assertFalse(GitCredentialConfig.shouldResetGlobalCredentialHelpers(listOf("store"), true))
    }

    @Test
    fun `computes plaintext credential store scrub targets`() {
        val request = GitCredentialRequest.fromRepositoryUrl(
            repositoryUrl = "https://oauth2:token@code.cwoa.net:8443/group/repo.git",
            username = "oauth2",
            password = "token",
        )

        assertEquals(
            listOf(
                URI("https://code.cwoa.net:8443/"),
                URI("https://task-123.code.cwoa.net:8443/"),
                URI("http://task-123.code.cwoa.net:8443/"),
            ),
            request.credentialStoreUris("task-123"),
        )
    }

    @Test
    fun `uses credential username fallback when empty helper is not supported`() {
        val helperCommand = GitCredentialConfig.helperCommand(
            taskId = "task-1",
            jarPath = Paths.get("/tmp/fast git clone.jar"),
            javaPath = Paths.get("/usr/bin/java"),
        )
        val environment = GitCredentialConfig.environment(
            helperCommand = helperCommand,
            taskId = "task-1",
            supportsEmptyCredentialHelper = false,
        )

        assertEquals("3", environment["GIT_CONFIG_COUNT"])
        assertEquals("credential.username", environment["GIT_CONFIG_KEY_0"])
        assertEquals("task-1", environment["GIT_CONFIG_VALUE_0"])
        assertEquals("credential.helper", environment["GIT_CONFIG_KEY_1"])
        assertEquals(helperCommand, environment["GIT_CONFIG_VALUE_1"])
        assertEquals("credential.useHttpPath", environment["GIT_CONFIG_KEY_2"])
    }

    @Test
    fun `uses Java 8 compatible process output discard`() {
        val redirect = discardProcessOutput()

        assertEquals(ProcessBuilder.Redirect.Type.WRITE, redirect.type())
        assertTrue(redirect.file().path == "/dev/null" || redirect.file().path == "NUL")
    }

    @Test
    fun `computes git versions like checkout core`() {
        assertEquals(2300201L, GitVersionSupport.computeGitVersion("git version 2.30.2.windows.1"))
        assertEquals(2300155L, GitVersionSupport.computeGitVersion("git version 2.30.0.155.g66e871b"))
        assertEquals(2190100L, GitVersionSupport.computeGitVersion("git version 2.19.1"))
        assertEquals(2300100L, GitVersionSupport.computeGitVersion("git version 2.30.1 (Apple Git-130)"))
        assertEquals(2090000L, GitVersionSupport.computeVersionFromBits(2, 9, 0, 0))
    }

    @Test
    fun `helper stores default and task scoped credentials for job sharing`() {
        val backend = InMemoryCredentialBackend()
        runProgram(
            input = "protocol=https\nhost=code.cwoa.net\npath=group/repo.git\nusername=oauth2\npassword=tok#en\n\n",
            args = arrayOf("task-123", "devopsStore"),
            backend = backend,
        )

        val output = runProgram(
            input = "protocol=https\nhost=code.cwoa.net\npath=group/repo.git\n\n",
            args = arrayOf("task-123", "get"),
            backend = backend,
        )

        assertTrue(output.contains("username=oauth2"))
        assertTrue(output.contains("password=tok#en"))
        assertEquals(
            StoredGitCredential("oauth2", "tok#en"),
            backend.get(URI("https://code.cwoa.net/")),
        )
        assertEquals(
            StoredGitCredential("oauth2", "tok#en"),
            backend.get(URI("https://task-123.code.cwoa.net/")),
        )

        val sharedOutput = runProgram(
            input = "protocol=https\nhost=code.cwoa.net\npath=group/repo.git\n\n",
            args = arrayOf("get"),
            backend = backend,
        )
        assertTrue(sharedOutput.contains("username=oauth2"))
        assertTrue(sharedOutput.contains("password=tok#en"))

        runProgram(
            input = "protocol=https\nhost=code.cwoa.net\npath=group/repo.git\n\n",
            args = arrayOf("task-123", "devopsErase"),
            backend = backend,
        )
        assertNull(backend.get(URI("https://code.cwoa.net/")))
        assertNull(backend.get(URI("https://task-123.code.cwoa.net/")))
    }

    @Test
    fun `tightens an existing loose credential cache directory`() {
        val directory = Files.createTempDirectory("fast-git-clone-credential-")
        try {
            if (Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) == null) {
                return
            }
            Files.setPosixFilePermissions(
                directory,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )

            tightenExistingCredentialCacheDirectory(directory)

            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(directory),
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun runProgram(input: String, args: Array<String>, backend: GitCredentialBackend): String {
        val outputStream = ByteArrayOutputStream()
        GitCredentialHelperProgram(
            standardIn = ByteArrayInputStream(input.toByteArray()),
            standardOut = PrintStream(outputStream),
            store = backend,
        ).run(args)
        return outputStream.toString(Charsets.UTF_8.name())
    }

    private class InMemoryCredentialBackend : GitCredentialBackend {
        private val credentials = linkedMapOf<URI, StoredGitCredential>()

        override fun get(targetUri: URI): StoredGitCredential? = credentials[targetUri]

        override fun add(targetUri: URI, credential: StoredGitCredential) {
            credentials[targetUri] = credential
        }

        override fun delete(targetUri: URI) {
            credentials.remove(targetUri)
        }
    }
}