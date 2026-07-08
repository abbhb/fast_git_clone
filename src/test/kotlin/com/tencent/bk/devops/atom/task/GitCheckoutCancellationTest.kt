package com.tencent.bk.devops.atom.task

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        val coordinator = CriticalSectionCoordinator(gracePeriodMillis = 1000, logger = {})
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
    fun `interrupted command wait keeps child process running to completion`() {
        val coordinator = CriticalSectionCoordinator(gracePeriodMillis = 1000, logger = {})
        val marker = Files.createTempFile("fast-git-clone-marker", ".txt")
        Files.deleteIfExists(marker)
        val commandThread = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<CommandResult> {
                commandThread.set(Thread.currentThread())
                CommandCancellationContext.withCoordinator(coordinator) {
                    runCommand(
                        listOf(
                            "/bin/sh",
                            "-c",
                            "sleep 0.2; printf done > '${marker.toAbsolutePath()}'",
                        ),
                        captureOutput = true,
                    )
                }
            }
            while (commandThread.get() == null) {
                Thread.sleep(10)
            }
            commandThread.get().interrupt()

            assertEquals(0, future.get(1, TimeUnit.SECONDS).exitCode)
            assertEquals("done", marker.toFile().readText())
            assertTrue(coordinator.isCancellationRequested())
        } finally {
            executor.shutdownNow()
            Files.deleteIfExists(marker)
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
    fun `detects incomplete git cache as unusable repository`() {
        val repoDir = Files.createTempDirectory("fast-git-clone-incomplete-repo")
        repoDir.resolve(".git").createDirectories()

        assertFalse(isUsableGitRepository(repoDir))

        repoDir.toFile().deleteRecursively()
    }

    @Test
    fun `detects initialized git cache as usable repository`() {
        val repoDir = Files.createTempDirectory("fast-git-clone-usable-repo")
        try {
            runCommand(listOf("git", "init", repoDir.toAbsolutePath().toString()), captureOutput = true)

            assertTrue(isUsableGitRepository(repoDir))
        } finally {
            repoDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `default cancellation wait does not time out active critical section`() {
        val messages = mutableListOf<String>()
        val coordinator = CriticalSectionCoordinator(logger = messages::add)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Boolean> {
                coordinator.runCriticalSection("git-checkout") {
                    entered.countDown()
                    release.await(1, TimeUnit.SECONDS)
                    true
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val cancelExecutor = Executors.newSingleThreadExecutor()
            try {
                val cancelFuture = cancelExecutor.submit<Boolean> { coordinator.requestCancellationAndWait() }
                Thread.sleep(100)
                assertFalse(cancelFuture.isDone)
                release.countDown()
                assertTrue(cancelFuture.get(1, TimeUnit.SECONDS))
            } finally {
                cancelExecutor.shutdownNow()
            }
            assertTrue(future.get(1, TimeUnit.SECONDS))
            assertTrue(messages.any { it.contains("持续等待直到当前 Git 任务收尾") })
        } finally {
            executor.shutdownNow()
        }
    }
}
