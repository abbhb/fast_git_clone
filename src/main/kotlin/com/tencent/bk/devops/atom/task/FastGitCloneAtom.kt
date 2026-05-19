package com.tencent.bk.devops.atom.task

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bk.devops.atom.AtomContext
import com.tencent.bk.devops.atom.pojo.DataField
import com.tencent.bk.devops.atom.pojo.StringData
import com.tencent.bk.devops.atom.spi.AtomService
import com.tencent.bk.devops.atom.spi.TaskAtom
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

@AtomService(paramClass = FastGitCloneParam::class)
class FastGitCloneAtom : TaskAtom<FastGitCloneParam> {
    override fun execute(atomContext: AtomContext<FastGitCloneParam>) {
        val result = FastGitCloneRunner().run(atomContext.param)
        atomContext.result.data = result.mapValuesTo(linkedMapOf()) { StringData(it.value) as DataField }
    }
}

class FastGitCloneRunner {
    fun run(param: FastGitCloneParam): Map<String, String> {
        val gitUsername = requireValue(param.gitUsername, "GIT_USERNAME")
        val gitToken = requireValue(param.gitToken, "GIT_TOKEN")
        val gitHost = requireValue(param.gitHost, "GIT_HOST")
        val repoUrl = requireValue(param.kingeyeGitRepo, "KINGEYE_GIT_REPO")
        val targetBranch = parseBranchName(requireValue(param.branch, "BRANCH"))

        val cacheDir = normalizePath(param.cacheDir.ifBlank { "\${{ci.workspace}}/git-cache/kingeye" }, param)
        val targetDir = normalizePath(param.targetDir.ifBlank { "\${{ci.workspace}}/\${{ci.build_num}}/kingeye_source" }, param)
        val defaultWorkDir = normalizePath(param.defaultWorkDir.ifBlank { "\${{ci.workspace}}" }, param)

        validateTools()
        validatePaths(cacheDir, targetDir, defaultWorkDir)

        Log.info("目标分支: $targetBranch")
        Log.info("仓库地址: $repoUrl")
        Log.info("缓存目录: $cacheDir")
        Log.info("目标目录: $targetDir")

        configureGitCredentials(gitUsername, gitToken, gitHost)
        syncGitCache(repoUrl, targetBranch, cacheDir)
        val commitId = getCommitId(cacheDir)
        rsyncToTarget(cacheDir, targetDir)

        Log.info("分支: $targetBranch")
        Log.info("Commit: $commitId")
        Log.info("工作目录: $targetDir")

        return linkedMapOf(
            "targetBranch" to targetBranch,
            "commitId" to commitId,
            "targetDir" to targetDir,
            "cacheDir" to cacheDir,
        )
    }

    private fun parseBranchName(value: String): String {
        val branchText = value.trim()
        if (branchText.isEmpty()) {
            throw PluginException("branchName 解析失败")
        }

        runCatching { objectMapper.readTree(branchText) }
            .getOrNull()
            ?.takeIf(JsonNode::isObject)
            ?.path("branchName")
            ?.asText("")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            ?.let { return it }

        Regex("""branchName['"]?\s*[:=]\s*['"]?([^,}'"]+)""")
            .find(branchText)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            ?.let { return it }

        if (!branchText.contains("branchName") && !branchText.equals("null", ignoreCase = true)) {
            return branchText
        }

        throw PluginException("branchName 解析失败")
    }

    private fun normalizePath(pathValue: String, param: FastGitCloneParam): String {
        val resolved = pathValue.trim()
            .replace("\${{ci.workspace}}", getWorkspace(param))
            .replace("\${{ci.build_num}}", getBuildNum(param))
        return Paths.get(resolved).toAbsolutePath().normalize().toString()
    }

    private fun getWorkspace(param: FastGitCloneParam): String =
        param.bkWorkspace?.takeIf { it.isNotBlank() }
            ?: System.getenv("BK_CI_WORKSPACE")
            ?: System.getenv("WORKSPACE")
            ?: Paths.get("").toAbsolutePath().toString()

    private fun getBuildNum(param: FastGitCloneParam): String =
        param.pipelineBuildNum?.takeIf { it.isNotBlank() }
            ?: System.getenv("BK_CI_BUILD_NUM")
            ?: "0"

    private fun validateTools() {
        listOf("git", "rsync").forEach { toolName ->
            if (runCommand(listOf("/usr/bin/env", "which", toolName), check = false, captureOutput = true).exitCode != 0) {
                throw PluginException("未找到命令: $toolName")
            }
        }
    }

    private fun validatePaths(cacheDir: String, targetDir: String, defaultWorkDir: String) {
        val targetPath = Paths.get(targetDir).toAbsolutePath().normalize()
        val defaultWorkPath = Paths.get(defaultWorkDir).toAbsolutePath().normalize()
        val cachePath = Paths.get(cacheDir).toAbsolutePath().normalize()

        if (targetPath == defaultWorkPath) {
            throw PluginException("目标代码目录不能与默认工作目录相同: $targetPath")
        }
        if (targetPath == cachePath) {
            throw PluginException("目标代码目录不能与 Git 缓存目录相同: $targetPath")
        }
        if (!targetPath.startsWith(defaultWorkPath)) {
            Log.warning("目标代码目录不在默认工作目录下，请确认配置符合预期: $targetPath")
        }
    }

    private fun configureGitCredentials(gitUsername: String, gitToken: String, gitHost: String) {
        runCommand(listOf("git", "config", "--global", "credential.helper", "store"))
        val credentialsPath = Paths.get(System.getProperty("user.home"), ".git-credentials")
        val normalizedHost = gitHost.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        val credentialLine = "https://${urlEncode(gitUsername)}:${urlEncode(gitToken)}@$normalizedHost"
        val keptLines = if (credentialsPath.exists()) {
            credentialsPath.readText().lineSequence()
                .filter { !it.trim().endsWith("@$normalizedHost") }
                .toMutableList()
        } else {
            mutableListOf()
        }
        keptLines.add(credentialLine)
        credentialsPath.writeText(keptLines.joinToString("\n") + "\n")
        runCatching {
            Files.setPosixFilePermissions(
                credentialsPath,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        Log.info("Git credential store 已配置: $normalizedHost")
    }

    private fun syncGitCache(repoUrl: String, targetBranch: String, cacheDir: String) {
        val cachePath = Paths.get(cacheDir)
        if (!cachePath.resolve(".git").isDirectory()) {
            Log.info("首次 clone 仓库...")
            clearIncompleteCache(cachePath)
            cloneRepositoryWithRetry(repoUrl, cacheDir)
        }

        Log.info("同步远程仓库...")
        runCommand(listOf("git", "remote", "set-url", "origin", repoUrl), cwd = cachePath)
        runCommand(listOf("git", "fsck"), cwd = cachePath, check = false)
        runCommand(listOf("git", "fetch", "origin", "--prune", "--tags"), cwd = cachePath)
        runCommand(listOf("git", "reset", "--hard"), cwd = cachePath)
        runCommand(listOf("git", "clean", "-fdx"), cwd = cachePath)

        if (localBranchExists(cachePath, targetBranch)) {
            runCommand(listOf("git", "checkout", "-f", targetBranch), cwd = cachePath)
        } else {
            runCommand(listOf("git", "checkout", "-B", targetBranch), cwd = cachePath)
        }

        runCommand(listOf("git", "reset", "--hard", "origin/$targetBranch"), cwd = cachePath)
        runCommand(listOf("git", "clean", "-fdx"), cwd = cachePath)
    }

    private fun cloneRepositoryWithRetry(repoUrl: String, cacheDir: String) {
        val cachePath = Paths.get(cacheDir)
        var lastError: PluginException? = null
        repeat(CLONE_MAX_ATTEMPTS) { index ->
            val attempt = index + 1
            if (attempt > 1) {
                Log.warning("首次 clone 第 $attempt 次重试，先清理上一次失败留下的缓存目录: $cacheDir")
                clearIncompleteCache(cachePath)
            }
            try {
                cachePath.parent?.createDirectories()
                runCommand(listOf("git", "clone", "--origin", "origin", repoUrl, cacheDir))
                return
            } catch (error: PluginException) {
                lastError = error
                Log.warning("首次 clone 第 $attempt 次失败: ${error.message}")
            }
        }
        clearIncompleteCache(cachePath)
        throw lastError ?: PluginException("首次 clone 仓库失败")
    }

    private fun clearIncompleteCache(cachePath: Path) {
        if (cachePath.exists()) {
            cachePath.toFile().deleteRecursively()
        }
    }

    private fun localBranchExists(cacheDir: Path, targetBranch: String): Boolean =
        runCommand(
            listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/$targetBranch"),
            cwd = cacheDir,
            check = false,
            captureOutput = true,
        ).exitCode == 0

    private fun getCommitId(cacheDir: String): String =
        runCommand(listOf("git", "rev-parse", "HEAD"), cwd = Paths.get(cacheDir), captureOutput = true).stdout.trim()

    private fun rsyncToTarget(cacheDir: String, targetDir: String) {
        Log.info("同步代码到工作目录...")
        Paths.get(targetDir).createDirectories()
        runCommand(
            listOf(
                "rsync",
                "-a",
                "--delete",
                "--exclude=.git",
                ensureTrailingSlash(cacheDir),
                ensureTrailingSlash(targetDir),
            ),
        )
    }

    private fun ensureTrailingSlash(pathValue: String): String =
        if (pathValue.endsWith(java.io.File.separator)) pathValue else pathValue + java.io.File.separator

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun requireValue(value: String, key: String): String =
        value.trim().takeIf { it.isNotEmpty() } ?: throw PluginException("缺少必填参数: $key")
}

private fun runCommand(
    command: List<String>,
    cwd: Path? = null,
    check: Boolean = true,
    captureOutput: Boolean = false,
): CommandResult {
    Log.info("执行命令: ${maskCommand(command).joinToString(" ")}")
    val processBuilder = ProcessBuilder(command)
    if (cwd != null) {
        processBuilder.directory(cwd.toFile())
    }
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    if (captureOutput) {
        processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
        processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
    } else {
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT)
    }

    val process = processBuilder.start()
    if (captureOutput) {
        process.inputStream.copyTo(stdout)
        process.errorStream.copyTo(stderr)
    }
    val exitCode = process.waitFor()
    val result = CommandResult(
        exitCode = exitCode,
        stdout = stdout.toString(StandardCharsets.UTF_8.name()),
        stderr = stderr.toString(StandardCharsets.UTF_8.name()),
    )
    if (check && exitCode != 0) {
        val errorMessage = result.stderr.trim().ifBlank { "命令执行失败" }
        throw PluginException("$errorMessage: ${maskCommand(command).joinToString(" ")}")
    }
    return result
}

private fun maskCommand(command: List<String>): List<String> =
    command.map { item ->
        if (item.contains('@') && item.contains("://")) {
            item.replace(Regex("(https?://)[^/@:]+:[^/@]+@"), "$1***:***@")
        } else {
            item
        }
    }

class PluginException(message: String) : RuntimeException(message)

private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

private object Log {
    fun info(message: String) = println("##[info]$message")
    fun warning(message: String) = println("##[warning]$message")
}

private val objectMapper = ObjectMapper()

private const val CLONE_MAX_ATTEMPTS = 3
