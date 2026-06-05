package com.tencent.bk.devops.atom.task

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitCheckoutCancellationTest {
    @Test
    fun `waits for active critical section to finish after cancellation`() {
        val messages = mutableListOf<String>()
        val coordinator = CriticalSectionCoordinator(gracePeriodMillis = 1000, logger = messages::add)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Boolean> {
                coordinator.runCriticalSection("git-fetch") {
                    coordinator.onCommandStart("git fetch origin")
                    entered.countDown()
                    release.await(1, TimeUnit.SECONDS)
                    coordinator.onCommandEnd()
                    true
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val cancelExecutor = Executors.newSingleThreadExecutor()
            try {
                val cancelFuture = cancelExecutor.submit<Boolean> { coordinator.requestCancellationAndWait() }
                Thread.sleep(100)
                release.countDown()
                assertTrue(cancelFuture.get(1, TimeUnit.SECONDS))
            } finally {
                cancelExecutor.shutdownNow()
            }
            assertTrue(future.get(1, TimeUnit.SECONDS))
            assertTrue(coordinator.isCancellationRequested())
            assertTrue(messages.any { it.contains("关键区[git-fetch] 已完成") })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `times out when critical section does not finish in grace period`() {
        val coordinator = CriticalSectionCoordinator(gracePeriodMillis = 50, logger = {})
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit<Unit> {
                coordinator.runCriticalSection("git-clone") {
                    entered.countDown()
                    release.await(1, TimeUnit.SECONDS)
                }
                Unit
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(coordinator.requestCancellationAndWait())
            release.countDown()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `tracks command on thread local coordinator during runCommand`() {
        val coordinator = CriticalSectionCoordinator(gracePeriodMillis = 200, logger = {})
        val script = Files.createTempFile("fast-git-clone", ".sh")
        script.toFile().setExecutable(true)
        script.writeText("#!/bin/sh\nsleep 0.2\n")
        val entered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit<Unit> {
                CommandCancellationContext.withCoordinator(coordinator) {
                    coordinator.runCriticalSection("git-fetch") {
                        entered.countDown()
                        runCommand(listOf(script.toAbsolutePath().toString()), captureOutput = true)
                    }
                }
                Unit
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(coordinator.requestCancellationAndWait())
        } finally {
            executor.shutdownNow()
            Files.deleteIfExists(script)
        }
    }

    @Test
    fun `cleans up residual git lock files and merge marker`() {
        val repoDir = Files.createTempDirectory("fast-git-clone-repo")
        val gitDir = repoDir.resolve(".git").createDirectories()
        gitDir.resolve("index.lock").writeText("lock")
        gitDir.resolve("config.lock").writeText("lock")
        gitDir.resolve("MERGE_HEAD").writeText("merge")
        gitDir.resolve("refs/heads").createDirectories().resolve("main.lock").writeText("lock")
        gitDir.resolve("modules/submodule").createDirectories().resolve("config.lock").writeText("lock")

        val removed = GitCacheRecovery.cleanupResidualState(repoDir)

        assertEquals(4, removed.size)
        assertFalse(Files.exists(gitDir.resolve("index.lock")))
        assertFalse(Files.exists(gitDir.resolve("config.lock")))
        assertFalse(Files.exists(gitDir.resolve("refs/heads/main.lock")))
        assertFalse(Files.exists(gitDir.resolve("modules/submodule/config.lock")))
        assertTrue(GitCacheRecovery.hasInterruptedMerge(repoDir))
        repoDir.toFile().deleteRecursively()
    }

    @Test
    fun `canceled coordinator causes follow-up stages to stop`() {
        val coordinator = CriticalSectionCoordinator(gracePeriodMillis = 100, logger = {})
        val runner = FastGitCloneRunner(coordinator)
        coordinator.requestCancellationAndWait()

        val method = runner.javaClass.getDeclaredMethod("ensureNotCanceled", String::class.java)
        method.isAccessible = true

        val error = kotlin.runCatching {
            method.invoke(runner, "stop")
        }.exceptionOrNull()

        val cause = when (error) {
            is java.lang.reflect.InvocationTargetException -> error.targetException
            else -> error
        }
        assertTrue(cause is PluginException)
        assertEquals("stop", cause?.message)
    }
}
