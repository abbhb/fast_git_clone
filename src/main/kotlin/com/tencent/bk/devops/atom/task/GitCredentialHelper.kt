package com.tencent.bk.devops.atom.task

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

object FastGitCredentialHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            GitCredentialHelperProgram(System.`in`, System.out).run(args)
        } catch (error: Throwable) {
            System.err.println("fast_git_clone credential helper failed: ${error.message ?: error.javaClass.name}")
        }
    }
}

internal class GitCredentialHelperProgram(
    private val standardIn: InputStream,
    private val standardOut: PrintStream,
    private val store: GitCredentialBackend = SystemGitCredentialBackend(),
) {
    fun run(args: Array<String>) {
        val action = args.firstOrNull { it in setOf("get", "fill", "store", "erase", "devopsStore", "devopsErase") }
            ?: return
        val taskId = args.firstOrNull { it != action && !it.contains("?") }?.takeIf { it.isNotBlank() }
        when (action) {
            "get", "fill" -> get(taskId)
            "store", "devopsStore" -> store(taskId)
            "erase", "devopsErase" -> erase(taskId)
        }
    }

    private fun get(taskId: String?) {
        val request = GitCredentialRequest.readFrom(standardIn)
        val credential = taskId
            ?.let { store.get(request.toTaskUri(it)) }
            ?.takeIf { !it.isEmpty }
            ?: store.get(request.targetUri)?.takeIf { !it.isEmpty }
            ?: return
        standardOut.print(request.withCredential(credential).toGitInput())
    }

    private fun store(taskId: String?) {
        val request = GitCredentialRequest.readFrom(standardIn)
        val credential = request.credential ?: return
        store.add(request.targetUri, credential)
        if (!taskId.isNullOrBlank()) {
            request.compatibleTaskUris(taskId).forEach { store.add(it, credential) }
        }
    }

    private fun erase(taskId: String?) {
        val request = GitCredentialRequest.readFrom(standardIn)
        store.delete(request.targetUri)
        if (!taskId.isNullOrBlank()) {
            request.compatibleTaskUris(taskId).forEach { store.delete(it) }
        }
    }
}

internal class GitCredentialSession private constructor(
    private val request: GitCredentialRequest,
    private val taskId: String,
    private val helperCommand: String,
    private val globalHelperCommand: String,
    private val store: GitCredentialBackend,
) {
    fun store() {
        val credential = request.credential ?: throw PluginException("Git HTTP 凭证为空")
        store.add(request.targetUri, credential)
        request.compatibleTaskUris(taskId).forEach { store.add(it, credential) }
        GitCredentialConfig.configureGlobalHelper(globalHelperCommand)
        Log.info("Git credential helper 已写入安全凭证后端: ${request.host}")
    }

    fun gitEnvironment(): Map<String, String> = GitCredentialConfig.environment(helperCommand)

    fun configureLocalRepository(repoDir: Path) {
        if (!repoDir.resolve(".git").isDirectory()) {
            return
        }
        runCommand(
            listOf("git", "config", "--local", "--unset-all", "credential.helper"),
            cwd = repoDir,
            check = false,
            captureOutput = true,
        )
        runCommand(listOf("git", "config", "--local", "--add", "credential.helper", ""), cwd = repoDir)
        runCommand(listOf("git", "config", "--local", "--add", "credential.helper", helperCommand), cwd = repoDir)
        runCommand(listOf("git", "config", "--local", "credential.useHttpPath", "false"), cwd = repoDir)
        Log.info("Git credential helper 已配置到本地仓库")
    }

    companion object {
        fun create(repositoryUrl: String, username: String, password: String): GitCredentialSession {
            val request = GitCredentialRequest.fromRepositoryUrl(repositoryUrl, username, password)
            val taskId = GitCredentialConfig.taskId()
            val helperCommand = GitCredentialConfig.helperCommand(taskId = taskId)
            val globalHelperCommand = GitCredentialConfig.helperCommand()
            return GitCredentialSession(
                request = request,
                taskId = taskId,
                helperCommand = helperCommand,
                globalHelperCommand = globalHelperCommand,
                store = SystemGitCredentialBackend(),
            )
        }

        fun cleanup(repositoryUrl: String, cacheDir: Path? = null) {
            val request = GitCredentialRequest.fromRepositoryUrl(repositoryUrl)
            val taskId = GitCredentialConfig.taskId()
            val store = SystemGitCredentialBackend()
            store.delete(request.targetUri)
            request.compatibleTaskUris(taskId).forEach { store.delete(it) }
            cacheDir?.let(GitCredentialConfig::removeLocalHelper)
            GitCredentialConfig.removeGlobalHelper()
            Log.info("Git credential helper 已清理: ${request.host}")
        }
    }
}

internal object GitCredentialConfig {
    fun normalizeHost(value: String): String {
        val trimmed = value.trim()
        val host = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                runCatching { hostWithPort(URI(trimmed)) }.getOrDefault("")
            trimmed.contains('@') && trimmed.contains(':') ->
                trimmed.substringAfter('@').substringBefore(':')
            else -> trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .trimEnd('/')
        }.trim().lowercase(Locale.ROOT)
        return host.ifBlank { throw PluginException("无法从 Git 地址解析域名") }
    }

    fun helperCommand(
        taskId: String? = null,
        jarPath: Path = installHelperJar(),
        javaPath: Path = javaExecutable(),
    ): String {
        val taskArg = taskId?.takeIf { it.isNotBlank() }?.let { " ${shellQuote(it)}" }.orEmpty()
        return "!${shellQuote(javaPath.toString())} -cp ${shellQuote(jarPath.toString())} " +
            "${FastGitCredentialHelper::class.java.name}$taskArg"
    }

    fun configureGlobalHelper(helperCommand: String) {
        val configuredHelpers = runCommand(
            listOf("git", "config", "--global", "--get-all", "credential.helper"),
            check = false,
            captureOutput = true,
        ).stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (configuredHelpers.none { it.contains(FastGitCredentialHelper::class.java.name) }) {
            runCommand(listOf("git", "config", "--global", "--add", "credential.helper", helperCommand))
            Log.info("Git credential helper 已配置到全局，用于当前 Job 后续步骤复用")
        }
    }

    fun removeGlobalHelper() {
        runCommand(
            listOf(
                "git",
                "config",
                "--global",
                "--unset-all",
                "credential.helper",
                ".*${FastGitCredentialHelper::class.java.simpleName}.*",
            ),
            check = false,
            captureOutput = true,
        )
    }

    fun removeLocalHelper(repoDir: Path) {
        if (!repoDir.resolve(".git").isDirectory()) {
            return
        }
        runCommand(
            listOf("git", "config", "--local", "--unset-all", "credential.helper"),
            cwd = repoDir,
            check = false,
            captureOutput = true,
        )
        runCommand(
            listOf("git", "config", "--local", "--unset-all", "credential.useHttpPath"),
            cwd = repoDir,
            check = false,
            captureOutput = true,
        )
    }

    fun environment(helperCommand: String): Map<String, String> = linkedMapOf(
        "GIT_TERMINAL_PROMPT" to "0",
        "GIT_CONFIG_COUNT" to "3",
        "GIT_CONFIG_KEY_0" to "credential.helper",
        "GIT_CONFIG_VALUE_0" to "",
        "GIT_CONFIG_KEY_1" to "credential.helper",
        "GIT_CONFIG_VALUE_1" to helperCommand,
        "GIT_CONFIG_KEY_2" to "credential.useHttpPath",
        "GIT_CONFIG_VALUE_2" to "false",
    )

    fun taskId(): String {
        val candidates = listOf(
            System.getenv("BK_CI_BUILD_TASK_ID"),
            listOfNotNull(
                System.getenv("BK_CI_PIPELINE_ID"),
                System.getenv("BK_CI_BUILD_ID"),
                System.getenv("BK_CI_BUILD_JOB_ID"),
            ).joinToString("-").takeIf { it.isNotBlank() },
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }?.let(::sanitizeHostPart)
            ?: "fast-git-clone-${randomSuffix()}"
    }

    fun scopedHost(host: String, taskId: String): String {
        val normalizedHost = normalizeHost(host)
        val hostOnly = normalizedHost.substringBefore(':')
        val port = normalizedHost.substringAfter(':', "")
        val scopedHost = "${sanitizeHostPart(taskId)}.$hostOnly"
        return if (port.isBlank()) scopedHost else "$scopedHost:$port"
    }

    private fun installHelperJar(): Path {
        val sourcePath = Paths.get(
            FastGitCredentialHelper::class.java.protectionDomain.codeSource.location.toURI(),
        ).toAbsolutePath().normalize()
        if (sourcePath.isDirectory()) {
            return sourcePath
        }
        val helperDir = Paths.get(userHome(), ".fast_git_clone")
        helperDir.createDirectories()
        val targetPath = helperDir.resolve("fast_git_clone.jar")
        if (sourcePath != targetPath) {
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
        return targetPath
    }

    private fun javaExecutable(): Path {
        val executable = if (isWindows()) "java.exe" else "java"
        return Paths.get(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun hostWithPort(uri: URI): String {
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        return if (host.isNotBlank() && uri.port >= 0) "$host:${uri.port}" else host
    }

    private fun sanitizeHostPart(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9-]+"), "-")
        .trim('-')
        .ifBlank { "task" }

    private fun randomSuffix(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

internal data class GitCredentialRequest(
    val protocol: String,
    val host: String,
    val path: String? = null,
    val username: String? = null,
    val password: String? = null,
) {
    val targetUri: URI get() = URI("$protocol://${GitCredentialConfig.normalizeHost(host)}/")
    val credential: StoredGitCredential? get() =
        if (username.isNullOrBlank() || password == null) null else StoredGitCredential(username, password)

    fun toTaskUri(taskId: String): URI = URI("$protocol://${GitCredentialConfig.scopedHost(host, taskId)}/")

    fun compatibleTaskUris(taskId: String): List<URI> = listOf("https", "http")
        .map { URI("$it://${GitCredentialConfig.scopedHost(host, taskId)}/") }
        .distinct()

    fun withCredential(credential: StoredGitCredential): GitCredentialRequest = copy(
        username = credential.username,
        password = credential.password,
    )

    fun toGitInput(): String {
        val builder = StringBuilder()
        builder.append("protocol=").append(protocol).append('\n')
        builder.append("host=").append(GitCredentialConfig.normalizeHost(host)).append('\n')
        if (!path.isNullOrBlank()) {
            builder.append("path=").append(path).append('\n')
        }
        if (!username.isNullOrBlank()) {
            builder.append("username=").append(username).append('\n')
        }
        if (password != null) {
            builder.append("password=").append(password).append('\n')
        }
        builder.append('\n')
        return builder.toString()
    }

    companion object {
        fun fromRepositoryUrl(
            repositoryUrl: String,
            username: String? = null,
            password: String? = null,
        ): GitCredentialRequest {
            val uri = runCatching { URI(repositoryUrl.trim()) }.getOrNull()
            val protocol = uri?.scheme?.takeIf { it.equals("http", true) || it.equals("https", true) } ?: "https"
            val host = uri?.let { GitCredentialConfig.normalizeHost(repositoryUrl) }
                ?: GitCredentialConfig.normalizeHost(repositoryUrl)
            val path = uri?.path?.trimStart('/')?.takeIf { it.isNotBlank() }
            return GitCredentialRequest(protocol, host, path, username, password)
        }

        fun readFrom(inputStream: InputStream): GitCredentialRequest {
            val values = linkedMapOf<String, String>()
            inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty()) {
                        break
                    }
                    val pair = line.split("=", limit = 2)
                    if (pair.size == 2) {
                        values[pair[0]] = pair[1]
                    }
                }
            }
            val protocol = values["protocol"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Git credential protocol 为空")
            val host = values["host"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Git credential host 为空")
            return GitCredentialRequest(
                protocol = protocol,
                host = host,
                path = values["path"],
                username = values["username"],
                password = values["password"],
            )
        }
    }
}

internal interface GitCredentialBackend {
    fun get(targetUri: URI): StoredGitCredential?
    fun add(targetUri: URI, credential: StoredGitCredential)
    fun delete(targetUri: URI)
}

internal data class StoredGitCredential(val username: String, val password: String) {
    val isEmpty: Boolean get() = username.isBlank() && password.isBlank()
}

internal class SystemGitCredentialBackend : GitCredentialBackend {
    override fun get(targetUri: URI): StoredGitCredential? {
        val output = invoke(resolveBackend(), "get", targetUri)
        if (output.exitCode != 0 || output.stdout.isBlank()) {
            return null
        }
        var username = ""
        var password = ""
        output.stdout.lineSequence().forEach { line ->
            val pair = line.split("=", limit = 2)
            if (pair.size == 2) {
                when (pair[0]) {
                    "username" -> username = pair[1]
                    "password" -> password = pair[1]
                }
            }
        }
        return StoredGitCredential(username, password)
    }

    override fun add(targetUri: URI, credential: StoredGitCredential) {
        invoke(resolveBackend(), "store", targetUri, credential)
    }

    override fun delete(targetUri: URI) {
        invoke(resolveBackend(), "erase", targetUri)
    }

    private fun resolveBackend(): List<String> {
        return when {
            isMac() -> {
                val osxKeychain = listOf("credential-osxkeychain")
                if (helperExists(osxKeychain.first())) osxKeychain else credentialCacheHelper()
            }
            isWindows() -> listOf(
                listOf("credential-manager-core"),
                listOf("credential-manager"),
                listOf("credential-wincred"),
            ).firstOrNull { helperExists(it.first()) }
                ?: throw PluginException("当前 Windows 环境没有可用的 Git Credential Manager")
            else -> credentialCacheHelper()
        }
    }

    private fun invoke(
        helperArgs: List<String>,
        action: String,
        targetUri: URI,
        credential: StoredGitCredential? = null,
    ): GitCredentialProcessResult {
        val input = gitCredentialInput(targetUri, credential)
        val processBuilder = ProcessBuilder(listOf("git") + helperArgs + action)
        if (System.getenv("HOME").isNullOrBlank()) {
            processBuilder.environment()["HOME"] = userHome()
        }
        val process = processBuilder.start()
        process.outputStream.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        process.inputStream.copyTo(stdout)
        process.errorStream.copyTo(stderr)
        val exitCode = process.waitFor()
        if (exitCode != 0 && action != "get") {
            throw PluginException(
                "Git credential helper 执行失败: ${maskMessage(stderr.toString(StandardCharsets.UTF_8.name()).trim())}",
            )
        }
        return GitCredentialProcessResult(
            exitCode = exitCode,
            stdout = stdout.toString(StandardCharsets.UTF_8.name()),
        )
    }

    private fun gitCredentialInput(targetUri: URI, credential: StoredGitCredential?): String {
        val builder = StringBuilder()
        builder.append("protocol=").append(targetUri.scheme).append('\n')
        builder.append("host=").append(GitCredentialConfig.normalizeHost(targetUri.host + portSuffix(targetUri))).append('\n')
        if (credential != null) {
            builder.append("username=").append(credential.username).append('\n')
            builder.append("password=").append(credential.password).append('\n')
        }
        builder.append('\n')
        return builder.toString()
    }

    private fun credentialCacheHelper(): List<String> {
        val socketPath = credentialCacheSocketPath()
        return listOf(
            "credential-cache",
            "--timeout=${TimeUnit.DAYS.toSeconds(7).toInt()}",
            "--socket=$socketPath",
        )
    }

    private fun credentialCacheSocketPath(): Path {
        var socketPath = Paths.get(userHome(), ".fast_git_clone")
        listOf(
            System.getenv("BK_CI_PIPELINE_ID"),
            System.getenv("BK_CI_BUILD_JOB_ID"),
        ).filter { !it.isNullOrBlank() }
            .forEach { socketPath = socketPath.resolve(it) }
        return socketPath.resolve("credential").resolve("socket")
    }

    private fun helperExists(helperName: String): Boolean {
        val stderr = ByteArrayOutputStream()
        val process = ProcessBuilder("git", helperName, "get")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        process.outputStream.use { it.write("\n".toByteArray(StandardCharsets.UTF_8)) }
        process.errorStream.copyTo(stderr)
        process.waitFor()
        return !stderr.toString(StandardCharsets.UTF_8.name()).contains("is not a git command")
    }

    private fun portSuffix(uri: URI): String = if (uri.port >= 0) ":${uri.port}" else ""
}

private data class GitCredentialProcessResult(val exitCode: Int, val stdout: String)

private fun userHome(): String = System.getenv("HOME")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")

private fun isMac(): Boolean = System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
