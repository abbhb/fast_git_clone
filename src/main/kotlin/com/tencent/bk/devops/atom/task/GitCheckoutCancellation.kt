package com.tencent.bk.devops.atom.task

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

internal class CriticalSectionCoordinator(
    private val gracePeriodMillis: Long = defaultGracePeriodMillis(),
    private val logger: (String) -> Unit = Log::warning,
) {
    private val cancellationRequested = AtomicBoolean(false)
    private val activeSectionName = AtomicReference<String?>(null)
    private val activeCommand = AtomicReference<String?>(null)
    private val activeSectionCompletion = AtomicReference<CountDownLatch?>(null)

    fun <T> runCriticalSection(name: String, block: () -> T): T {
        check(activeSectionName.compareAndSet(null, name)) {
            "critical section already active: ${activeSectionName.get()}"
        }
        val latch = CountDownLatch(1)
        activeSectionCompletion.set(latch)
        return try {
            block()
        } finally {
            activeCommand.set(null)
            activeSectionName.set(null)
            activeSectionCompletion.getAndSet(null)?.countDown()
        }
    }

    fun onCommandStart(command: String) {
        activeCommand.set(command)
    }

    fun onCommandEnd() {
        activeCommand.set(null)
    }

    fun isCancellationRequested(): Boolean = cancellationRequested.get()

    fun markCancellationRequested() {
        cancellationRequested.set(true)
    }

    fun requestCancellationAndWait(): Boolean {
        markCancellationRequested()
        val sectionName = activeSectionName.get() ?: return true
        val command = activeCommand.get()
        logger(
            buildString {
                append("检测到流水线取消/终止信号，当前处于关键区[")
                append(sectionName)
                append("]")
                if (!command.isNullOrBlank()) {
                    append("，正在执行命令: ")
                    append(command)
                }
                if (gracePeriodMillis > 0) {
                    append("，最多等待 ")
                    append(gracePeriodMillis)
                    append("ms 让当前 Git 任务收尾")
                } else {
                    append("，持续等待直到当前 Git 任务收尾")
                }
            },
        )
        val latch = activeSectionCompletion.get() ?: return true
        val finished = if (gracePeriodMillis > 0) {
            latch.await(gracePeriodMillis, TimeUnit.MILLISECONDS)
        } else {
            latch.await()
            true
        }
        if (finished) {
            logger("关键区[$sectionName] 已完成，允许插件退出")
        } else {
            logger("关键区[$sectionName] 在 ${gracePeriodMillis}ms 内未完成，交由 Agent 后续终止")
        }
        return finished
    }

    companion object {
        fun defaultGracePeriodMillis(): Long {
            val fromMillis = System.getenv("FAST_GIT_CLONE_CANCEL_GRACE_MILLIS")?.trim()
            if (!fromMillis.isNullOrBlank()) {
                return fromMillis.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_GRACE_PERIOD_SECONDS * 1000
            }
            val fromSeconds = System.getenv("FAST_GIT_CLONE_CANCEL_GRACE_SECONDS")?.trim()
            if (!fromSeconds.isNullOrBlank()) {
                return (fromSeconds.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_GRACE_PERIOD_SECONDS) * 1000
            }
            return WAIT_UNTIL_FINISHED
        }

        private const val DEFAULT_GRACE_PERIOD_SECONDS = 180L
        private const val WAIT_UNTIL_FINISHED = 0L
    }
}

internal object RuntimeCancellationController {
    val coordinator: CriticalSectionCoordinator by lazy {
        CriticalSectionCoordinator().also { coordinator ->
            Runtime.getRuntime().addShutdownHook(
                Thread(
                    { coordinator.requestCancellationAndWait() },
                    "fast-git-clone-shutdown-hook",
                ),
            )
        }
    }
}

internal object CommandCancellationContext {
    private val coordinatorRef = ThreadLocal<CriticalSectionCoordinator?>()

    fun currentCoordinator(): CriticalSectionCoordinator = coordinatorRef.get() ?: RuntimeCancellationController.coordinator

    fun <T> withCoordinator(coordinator: CriticalSectionCoordinator, block: () -> T): T {
        val previous = coordinatorRef.get()
        coordinatorRef.set(coordinator)
        return try {
            block()
        } finally {
            if (previous == null) {
                coordinatorRef.remove()
            } else {
                coordinatorRef.set(previous)
            }
        }
    }
}

internal object GitCacheRecovery {
    fun cleanupResidualState(repositoryPath: Path, logger: (String) -> Unit = Log::warning): List<Path> {
        val gitDir = repositoryPath.resolve(".git")
        if (!gitDir.isDirectory()) {
            return emptyList()
        }

        val removed = linkedSetOf<Path>()
        knownLockFiles(gitDir).forEach { path ->
            if (deleteIfExists(path)) {
                removed.add(path)
            }
        }
        listOf(gitDir.resolve("refs"), gitDir.resolve("modules")).forEach { root ->
            removed.addAll(deleteNestedLockFiles(root))
        }

        if (removed.isNotEmpty()) {
            logger("检测到上次异常中断遗留的 Git 锁文件，已清理 ${removed.size} 个")
        }
        return removed.toList()
    }

    fun hasInterruptedMerge(repositoryPath: Path): Boolean = repositoryPath.resolve(".git").resolve("MERGE_HEAD").exists()

    private fun knownLockFiles(gitDir: Path): List<Path> = listOf(
        gitDir.resolve("index.lock"),
        gitDir.resolve("shallow.lock"),
        gitDir.resolve("config.lock"),
        gitDir.resolve("packed-refs.lock"),
        gitDir.resolve("packed-refs.new"),
    )

    private fun deleteNestedLockFiles(root: Path): List<Path> {
        if (!root.exists()) {
            return emptyList()
        }
        val removed = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".lock") }
                .forEach { path ->
                    if (deleteIfExists(path)) {
                        removed.add(path)
                    }
                }
        }
        return removed
    }

    private fun deleteIfExists(path: Path): Boolean = runCatching {
        Files.deleteIfExists(path)
    }.getOrDefault(false)
}
