package com.tencent.bk.devops.atom.task

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bk.devops.atom.AtomContext
import com.tencent.bk.devops.atom.api.BaseApi
import com.tencent.bk.devops.atom.api.Header
import com.tencent.bk.devops.atom.api.SdkEnv
import com.tencent.bk.devops.atom.pojo.DataField
import com.tencent.bk.devops.atom.pojo.StringData
import com.tencent.bk.devops.atom.spi.AtomService
import com.tencent.bk.devops.atom.spi.TaskAtom
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.interfaces.DHPublicKey
import javax.crypto.spec.DHParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.bouncycastle.jce.provider.BouncyCastleProvider

@AtomService(paramClass = FastGitCloneParam::class)
class FastGitCloneAtom : TaskAtom<FastGitCloneParam> {
    override fun execute(atomContext: AtomContext<FastGitCloneParam>) {
        val result = FastGitCloneRunner().run(atomContext.param)
        atomContext.result.data = result.mapValuesTo(linkedMapOf()) { StringData(it.value) as DataField }
    }
}

class FastGitCloneRunner {
    fun run(param: FastGitCloneParam): Map<String, String> {
        val gitSource = resolveGitSource(param)
        val repoUrl = gitSource.repositoryUrl
        val targetBranch = parseBranchName(requireValue(param.branch, "BRANCH"))

        val cacheDir = normalizePath(param.cacheDir.ifBlank { "\${{ci.workspace}}/git-cache/kingeye" }, param)
        val targetDir = normalizePath(param.targetDir.ifBlank { "\${{ci.workspace}}/\${{ci.build_num}}/kingeye_source" }, param)
        val defaultWorkDir = normalizePath(param.defaultWorkDir.ifBlank { "\${{ci.workspace}}" }, param)
        val excludeGitDir = BooleanParam.parse(param.excludeGitDir, defaultValue = true, key = "EXCLUDE_GIT_DIR")

        validateTools()
        validatePaths(cacheDir, targetDir, defaultWorkDir)

        Log.info("目标分支: $targetBranch")
        Log.info("仓库来源: ${gitSource.repositoryType}")
        Log.info("仓库地址: ${maskUrl(repoUrl)}")
        if (gitSource.aliasName.isNotBlank()) {
            Log.info("代码库别名: ${gitSource.aliasName}")
        }
        Log.info("缓存目录: $cacheDir")
        Log.info("目标目录: $targetDir")
            Log.info("同步到目标目录时${if (excludeGitDir) "排除" else "保留"} .git 目录")

        val gitEnv = configureGitAuth(gitSource)
        try {
            syncGitCache(repoUrl, targetBranch, cacheDir, gitEnv)
        } finally {
            gitSource.auth.cleanup()
        }
        val commitId = getCommitId(cacheDir)
        rsyncToTarget(cacheDir, targetDir, excludeGitDir)

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

    private fun resolveGitSource(param: FastGitCloneParam): GitSource {
        return when (val repositoryType = param.repositoryType.ifBlank { RepositoryType.URL.name }.trim().uppercase()) {
            RepositoryType.URL.name -> GitSource(
                repositoryType = repositoryType,
                repositoryUrl = requireValue(param.kingeyeGitRepo, "KINGEYE_GIT_REPO"),
                aliasName = "",
                auth = GitAuth.Http(
                    username = requireValue(param.gitUsername, "GIT_USERNAME"),
                    password = requireValue(param.gitToken, "GIT_TOKEN"),
                ),
                gitHost = requireValue(param.gitHost, "GIT_HOST"),
            )
            RepositoryType.ID.name, RepositoryType.NAME.name -> {
                val repositoryId = when (repositoryType) {
                    RepositoryType.ID.name -> requireValue(param.repositoryHashId, "repositoryHashId")
                    else -> requireValue(param.repositoryName, "repositoryName")
                }
                val repository = repositoryApi.getRepository(repositoryId, repositoryType)
                val auth = resolveRepositoryAuth(repository)
                GitSource(
                    repositoryType = repositoryType,
                    repositoryUrl = requireValue(repository.url, "repository.url"),
                    aliasName = repository.aliasName,
                    auth = auth,
                    gitHost = hostFromUrl(repository.url),
                )
            }
            else -> throw PluginException("不支持的代码库来源: $repositoryType")
        }
    }

    private fun resolveRepositoryAuth(repository: RepositoryInfo): GitAuth {
        return if (repository.authType.equals("OAUTH", ignoreCase = true)) {
            val userId = requireValue(repository.userName, "repository.userName")
            Log.info("使用蓝盾代码库 OAuth 授权: userId=$userId")
            GitAuth.Http(username = "oauth2", password = repositoryApi.getOauthToken(repository, userId))
        } else {
            val credentialId = requireValue(repository.credentialId, "repository.credentialId")
            Log.info("使用蓝盾代码库绑定凭证: credentialId=$credentialId, authType=${repository.authType.ifBlank { "UNKNOWN" }}")
            resolveCredentialAuth(credentialId)
        }
    }

    private fun resolveCredentialAuth(credentialId: String): GitAuth {
        val credential = credentialApi.getCredential(credentialId)
        val credentialType = credential.credentialType.uppercase()
        return when (credentialType) {
            "ACCESSTOKEN", "ACCESS_TOKEN" -> GitAuth.Http(
                username = "oauth2",
                password = requireValue(credential.v1, "credential access token"),
            )
            "USERNAME_PASSWORD" -> GitAuth.Http(
                username = requireValue(credential.v1, "credential username"),
                password = requireValue(credential.v2, "credential password"),
            )
            "TOKEN_USERNAME_PASSWORD" -> GitAuth.Http(
                username = requireValue(credential.v2, "credential username"),
                password = requireValue(credential.v3, "credential password"),
            )
            "SSH_PRIVATEKEY", "SSH_PRIVATE_KEY" -> GitAuth.Ssh(
                privateKey = requireValue(credential.v1, "credential privateKey"),
                passphrase = credential.v2,
            )
            "TOKEN_SSH_PRIVATEKEY", "TOKEN_SSH_PRIVATE_KEY" -> GitAuth.Ssh(
                privateKey = requireValue(credential.v2, "credential privateKey"),
                passphrase = credential.v3,
            )
            else -> throw PluginException("不支持的凭证类型: ${credentialType.ifBlank { "UNKNOWN" }}")
        }
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
            val result = runCommand(listOf("/usr/bin/env", "which", toolName), check = false, captureOutput = true)
            if (result.exitCode != 0) {
                throw PluginException("缺少依赖命令: $toolName，请先在 Agent 机器安装后重试")
            }
            Log.info("依赖命令检查通过: $toolName -> ${result.stdout.trim()}")
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

    private fun configureGitAuth(gitSource: GitSource): Map<String, String> {
        return when (val auth = gitSource.auth) {
            is GitAuth.Http -> {
                configureGitCredentials(auth.username, auth.password, gitSource.gitHost)
                emptyMap()
            }
            is GitAuth.Ssh -> configureSshCredentials(auth)
        }
    }

    private fun configureGitCredentials(gitUsername: String, gitToken: String, gitHost: String) {
        runCommand(listOf("git", "config", "--global", "credential.helper", "store"))
        val credentialsPath = GitCredentialStore.credentialsPath()
        val normalizedHost = GitCredentialStore.normalizeHost(gitHost)
        val credentialLine = GitCredentialStore.credentialLine(gitUsername, gitToken, normalizedHost)
        val credentialLines = if (credentialsPath.exists()) {
            GitCredentialStore.updatedCredentialLines(
                existingLines = credentialsPath.readText().lineSequence(),
                normalizedHost = normalizedHost,
                credentialLine = credentialLine,
            )
        } else {
            listOf(credentialLine)
        }
        credentialsPath.writeText(credentialLines.joinToString("\n") + "\n")
        runCatching {
            Files.setPosixFilePermissions(
                credentialsPath,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        Log.info("Git credential store 已配置: $normalizedHost")
    }

    private fun configureSshCredentials(auth: GitAuth.Ssh): Map<String, String> {
        if (auth.passphrase.isNotBlank()) {
            throw PluginException("暂不支持带 passphrase 的 SSH 私钥凭证，请改用无 passphrase 私钥或 HTTPS 凭证")
        }
        val keyDir = Files.createTempDirectory("fast-git-clone-ssh-")
        val keyPath = keyDir.resolve("id_key")
        keyPath.writeText(auth.privateKey.trimEnd() + "\n")
        runCatching {
            Files.setPosixFilePermissions(
                keyPath,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        auth.keyDir = keyDir
        Log.info("SSH 私钥凭证已写入临时文件")
        return mapOf("GIT_SSH_COMMAND" to "ssh -i ${keyPath.toAbsolutePath()} -o StrictHostKeyChecking=no")
    }

    private fun syncGitCache(repoUrl: String, targetBranch: String, cacheDir: String, gitEnv: Map<String, String>) {
        val cachePath = Paths.get(cacheDir)
        if (!cachePath.resolve(".git").isDirectory()) {
            Log.info("首次 clone 仓库...")
            clearIncompleteCache(cachePath)
            cloneRepositoryWithRetry(repoUrl, cacheDir, gitEnv)
        }

        Log.info("同步远程仓库...")
        runCommand(listOf("git", "remote", "set-url", "origin", repoUrl), cwd = cachePath, env = gitEnv)
        runCommand(
            listOf("git", "config", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*"),
            cwd = cachePath,
            env = gitEnv,
        )
        runCommand(listOf("git", "fsck"), cwd = cachePath, check = false, env = gitEnv)
        fetchTargetBranch(cachePath, targetBranch, gitEnv)
        runCommand(listOf("git", "reset", "--hard"), cwd = cachePath, env = gitEnv)
        runCommand(listOf("git", "clean", "-fdx"), cwd = cachePath, env = gitEnv)

        if (localBranchExists(cachePath, targetBranch)) {
            runCommand(listOf("git", "checkout", "-f", targetBranch), cwd = cachePath, env = gitEnv)
        } else {
            runCommand(listOf("git", "checkout", "-B", targetBranch), cwd = cachePath, env = gitEnv)
        }

        runCommand(listOf("git", "reset", "--hard", "origin/$targetBranch"), cwd = cachePath, env = gitEnv)
        runCommand(listOf("git", "clean", "-fdx"), cwd = cachePath, env = gitEnv)
    }

    private fun fetchTargetBranch(cacheDir: Path, targetBranch: String, gitEnv: Map<String, String>) {
        val refspec = "+refs/heads/$targetBranch:refs/remotes/origin/$targetBranch"
        val result = runCommand(
            listOf("git", "fetch", "origin", "--prune", "--no-tags", refspec),
            cwd = cacheDir,
            check = false,
            captureOutput = true,
            env = gitEnv,
        )
        if (result.exitCode != 0 || !remoteBranchExists(cacheDir, targetBranch)) {
            val detail = listOf(result.stderr.trim(), result.stdout.trim())
                .filter { it.isNotBlank() }
                .joinToString("; ")
            throw PluginException(
                "远端分支不存在或无权限访问: origin/$targetBranch" +
                    detail.takeIf { it.isNotBlank() }?.let { "，Git 输出: ${maskMessage(it)}" }.orEmpty(),
            )
        }
    }

    private fun cloneRepositoryWithRetry(repoUrl: String, cacheDir: String, gitEnv: Map<String, String>) {
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
                runCommand(listOf("git", "clone", "--origin", "origin", repoUrl, cacheDir), env = gitEnv)
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

    private fun remoteBranchExists(cacheDir: Path, targetBranch: String): Boolean =
        runCommand(
            listOf("git", "show-ref", "--verify", "--quiet", "refs/remotes/origin/$targetBranch"),
            cwd = cacheDir,
            check = false,
            captureOutput = true,
        ).exitCode == 0

    private fun getCommitId(cacheDir: String): String =
        runCommand(listOf("git", "rev-parse", "HEAD"), cwd = Paths.get(cacheDir), captureOutput = true).stdout.trim()

    private fun rsyncToTarget(cacheDir: String, targetDir: String, excludeGitDir: Boolean) {
        Log.info("同步代码到工作目录...")
        Paths.get(targetDir).createDirectories()
        runCommand(RsyncCommand.build(cacheDir, targetDir, excludeGitDir))
    }

    private fun hostFromUrl(repoUrl: String): String = runCatching {
        GitCredentialStore.normalizeHost(repoUrl)
    }.getOrElse {
        throw PluginException("无法从仓库地址解析 Git 域名: ${maskUrl(repoUrl)}")
    }

    private fun maskUrl(repoUrl: String): String = repoUrl.replace(Regex("(https?://)[^/@:]+:[^/@]+@"), "$1***:***@")

    private fun requireValue(value: String, key: String): String =
        value.trim().takeIf { it.isNotEmpty() } ?: throw PluginException("缺少必填参数: $key")

    private val repositoryApi = RepositoryApi()
    private val credentialApi = CredentialApiClient()
}

internal object GitCredentialStore {
    fun credentialsPath(): Path {
        val gitHome = System.getenv("HOME")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        return Paths.get(gitHome, ".git-credentials")
    }

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

    fun credentialLine(gitUsername: String, gitToken: String, normalizedHost: String): String =
        "https://${urlEncode(gitUsername)}:${urlEncode(gitToken)}@$normalizedHost"

    fun updatedCredentialLines(
        existingLines: Sequence<String>,
        normalizedHost: String,
        credentialLine: String,
    ): List<String> {
        val keptLines = existingLines
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("://") }
            .filterNot { credentialHostMatches(it, normalizedHost) }
            .toMutableList()
        keptLines.add(credentialLine)
        return keptLines
    }

    fun credentialHostMatches(credentialLine: String, normalizedHost: String): Boolean {
        val uri = runCatching { URI(credentialLine) }.getOrNull() ?: return false
        return hostWithPort(uri) == normalizedHost.lowercase(Locale.ROOT)
    }

    private fun hostWithPort(uri: URI): String {
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        return if (host.isNotBlank() && uri.port >= 0) "$host:${uri.port}" else host
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

internal object BooleanParam {
    fun parse(value: String, defaultValue: Boolean, key: String): Boolean {
        val normalizedValue = value.trim().lowercase(Locale.ROOT)
        if (normalizedValue.isEmpty()) {
            return defaultValue
        }
        val negated = normalizedValue.startsWith("!")
        val booleanValue = if (negated) normalizedValue.removePrefix("!").trim() else normalizedValue
        val parsedValue = when (booleanValue) {
            "true", "1", "yes", "y", "on" -> true
            "false", "0", "no", "n", "off" -> false
            else -> throw PluginException("$key 只能填写 true/false、1/0、yes/no 或 on/off，支持在前面加 ! 取反，当前值: $value")
        }
        return if (negated) !parsedValue else parsedValue
    }
}

internal object RsyncCommand {
    fun build(cacheDir: String, targetDir: String, excludeGitDir: Boolean): List<String> {
        val command = mutableListOf("rsync", "-a", "--delete")
        if (excludeGitDir) {
            command.add("--exclude=.git")
        }
        command.add(ensureTrailingSlash(cacheDir))
        command.add(ensureTrailingSlash(targetDir))
        return command
    }

    private fun ensureTrailingSlash(pathValue: String): String =
        if (pathValue.endsWith(java.io.File.separator)) pathValue else pathValue + java.io.File.separator
}

private fun runCommand(
    command: List<String>,
    cwd: Path? = null,
    check: Boolean = true,
    captureOutput: Boolean = false,
    env: Map<String, String> = emptyMap(),
): CommandResult {
    Log.info("执行命令: ${maskCommand(command).joinToString(" ")}")
    val processBuilder = ProcessBuilder(command)
    if (cwd != null) {
        processBuilder.directory(cwd.toFile())
    }
    if (env.isNotEmpty()) {
        processBuilder.environment().putAll(env)
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
            maskMessage(item)
        } else {
            item
        }
    }

private fun maskMessage(message: String): String =
    message.replace(Regex("(https?://)[^/@:]+:[^/@]+@"), "$1***:***@")

class PluginException(message: String) : RuntimeException(message)

private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

private data class GitSource(
    val repositoryType: String,
    val repositoryUrl: String,
    val aliasName: String,
    val auth: GitAuth,
    val gitHost: String,
)

private sealed class GitAuth {
    open fun cleanup() = Unit

    data class Http(val username: String, val password: String) : GitAuth()

    data class Ssh(val privateKey: String, val passphrase: String) : GitAuth() {
        var keyDir: Path? = null

        override fun cleanup() {
            keyDir?.toFile()?.deleteRecursively()
        }
    }
}

private data class RepositoryInfo(
    val repoHashId: String,
    val aliasName: String,
    val url: String,
    val authType: String,
    val userName: String,
    val credentialId: String,
    val scmType: String,
    val scmCode: String,
    val apiPrefix: String,
)

private enum class RepositoryType {
    ID,
    NAME,
    URL,
}

private class RepositoryApi : BaseApi() {
    fun getRepository(repositoryId: String, repositoryType: String): RepositoryInfo {
        val encodedRepositoryId = encode(repositoryId)
        val failures = mutableListOf<String>()
        listOf("", "/ms").forEach { apiPrefix ->
            val path = "$apiPrefix/repository/api/build/repositories?repositoryId=$encodedRepositoryId&repositoryType=$repositoryType"
            requestJsonOrNull(path, failures)?.let { root ->
                parseRepositoryResult(root, repositoryId, repositoryType, apiPrefix)?.let { return it }
            }
        }

        getRepositoryFromUserList(repositoryId, repositoryType, failures)?.let { return it }

        throw PluginException(
            "获取蓝盾代码库信息失败: repositoryId=$repositoryId, repositoryType=$repositoryType. " +
                "已尝试路径: ${failures.joinToString("; ")}",
        )
    }

    private fun parseRepositoryResult(
        root: JsonNode,
        repositoryId: String,
        repositoryType: String,
        apiPrefix: String,
    ): RepositoryInfo? {
        val status = root.path("status").asInt(0)
        if (status != 0 || root.path("data").isMissingNode || root.path("data").isNull) {
            return null
        }
        return parseRepository(root.path("data"), apiPrefix)
    }

    private fun getRepositoryFromUserList(
        repositoryId: String,
        repositoryType: String,
        failures: MutableList<String>,
    ): RepositoryInfo? {
        val projectId = SdkEnv.getSdkHeader()[Header.AUTH_HEADER_PROJECT_ID].orEmpty()
        if (projectId.isBlank()) {
            failures.add("projectId is empty, skip user repository list")
            return null
        }
        val encodedProjectId = encode(projectId)
        listOf("", "/ms").forEach { apiPrefix ->
            val path = "$apiPrefix/repository/api/user/repositories/$encodedProjectId/hasPermissionList" +
                "?permission=USE&page=1&pageSize=5000"
            val root = requestJsonOrNull(path, failures) ?: return@forEach
            val candidates = repositoryCandidates(root.path("data"))
            val match = candidates.firstOrNull { node ->
                if (repositoryType == RepositoryType.ID.name) {
                    node.firstText("repoHashId", "repositoryHashId", "id") == repositoryId
                } else {
                    node.firstText("aliasName", "repositoryName", "name") == repositoryId
                }
            }
            if (match != null) {
                return parseRepository(match, apiPrefix)
            }
            failures.add("$path -> repository not found in list")
        }
        return null
    }

    private fun repositoryCandidates(data: JsonNode): List<JsonNode> {
        return when {
            data.isArray -> data.toList()
            data.path("records").isArray -> data.path("records").toList()
            data.path("items").isArray -> data.path("items").toList()
            data.path("list").isArray -> data.path("list").toList()
            data.path("data").isArray -> data.path("data").toList()
            data.isObject -> listOf(data)
            else -> emptyList()
        }
    }

    private fun parseRepository(data: JsonNode, apiPrefix: String): RepositoryInfo {
        val classType = data.path("@type").asText("")
        return RepositoryInfo(
            repoHashId = data.firstText("repoHashId", "repositoryHashId", "repositoryId", "id"),
            aliasName = data.firstText("aliasName", "repositoryName", "name"),
            url = data.firstText("url", "repositoryUrl"),
            authType = data.firstText("authType", "repoAuthType"),
            userName = data.firstText("userName", "username", "authorizedUser", "authorizer"),
            credentialId = data.firstText("credentialId", "ticketId"),
            scmType = data.firstText("scmType").ifBlank { classType },
            scmCode = data.firstText("scmCode"),
            apiPrefix = apiPrefix,
        )
    }

    fun getOauthToken(repository: RepositoryInfo, userId: String): String {
        val failures = mutableListOf<String>()
        listOf(repository.apiPrefix, "", "/ms").distinct().forEach { apiPrefix ->
            val path = when {
                repository.scmType.contains("GITHUB", ignoreCase = true) ->
                    "$apiPrefix/repository/api/build/oauth/github/${encode(userId)}"
                repository.scmType.contains("SCM", ignoreCase = true) && repository.repoHashId.isNotBlank() ->
                    "$apiPrefix/repository/api/build/oauth/scm/repo/${encode(repository.repoHashId)}"
                else ->
                    "$apiPrefix/repository/api/build/oauth/git/${encode(userId)}"
            }
            val root = requestJsonOrNull(path, failures) ?: return@forEach
            val status = root.path("status").asInt(0)
            val data = root.path("data")
            val accessToken = data.firstText("access_token", "accessToken")
            if (status == 0 && accessToken.isNotBlank()) {
                return accessToken
            }
        }
        throw PluginException("获取代码库 OAuth Token 失败: userId=$userId. 已尝试路径: ${failures.joinToString("; ")}")
    }

    private fun requestJsonOrNull(path: String, failures: MutableList<String>): JsonNode? {
        val responseContent = try {
            request(buildGet(path), "请求蓝盾接口失败")
        } catch (error: IOException) {
            failures.add("$path -> ${error.message}")
            return null
        } catch (error: RuntimeException) {
            failures.add("$path -> ${error.message ?: error.javaClass.simpleName}")
            return null
        }
        return objectMapper.readTree(responseContent)
    }
}

private class CredentialApiClient : BaseApi() {
    fun getCredential(credentialId: String): CredentialInfo {
        val keyPair = DH.initKey()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.publicKey)
        val path = "/ticket/api/build/credentials/${encode(credentialId)}?publicKey=${encode(publicKey)}"
        val responseContent = try {
            request(buildGet(path), "获取凭证失败")
        } catch (error: IOException) {
            throw PluginException("获取凭证失败: credentialId=$credentialId, message=${error.message}")
        }
        val root = objectMapper.readTree(responseContent)
        val status = root.path("status").asInt(0)
        val data = root.path("data")
        if (status != 0 || data.isMissingNode || data.isNull) {
            throw PluginException(
                "获取凭证失败: credentialId=$credentialId, message=${root.path("message").asText("empty response")}",
            )
        }

        val serverPublicKey = data.firstText("publicKey")
        return CredentialInfo(
            credentialType = data.firstText("credentialType", "type"),
            v1 = decryptCredentialValue(data.firstText("v1"), serverPublicKey, keyPair.privateKey),
            v2 = decryptCredentialValue(data.firstText("v2"), serverPublicKey, keyPair.privateKey),
            v3 = decryptCredentialValue(data.firstText("v3"), serverPublicKey, keyPair.privateKey),
            v4 = decryptCredentialValue(data.firstText("v4"), serverPublicKey, keyPair.privateKey),
        )
    }

    private fun decryptCredentialValue(value: String, serverPublicKey: String, privateKey: ByteArray): String {
        if (value.isBlank()) {
            return ""
        }
        if (serverPublicKey.isBlank()) {
            return value
        }
        return runCatching {
            val decoder = Base64.getDecoder()
            String(DH.decrypt(decoder.decode(value), decoder.decode(serverPublicKey), privateKey), StandardCharsets.UTF_8)
        }.getOrElse { value }
    }
}

private object DH {
    private const val KEY_ALGORITHM = "DH"
    private const val KEY_PROVIDER = "BC"
    private const val SECRET_ALGORITHM = "DES"
    private val p = BigInteger("16560215747140417249215968347342080587", 16)
    private val g = BigInteger("1234567890", 16)

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    fun initKey(): DHKeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM, KEY_PROVIDER)
        keyPairGenerator.initialize(DHParameterSpec(p, g, 128), SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()
        return DHKeyPair(keyPair.public.encoded, keyPair.private.encoded)
    }

    fun decrypt(data: ByteArray, publicKey: ByteArray, privateKey: ByteArray): ByteArray {
        val key = getSecretKey(publicKey, privateKey)
        val secretKey = SecretKeySpec(key, SECRET_ALGORITHM)
        val cipher = Cipher.getInstance(secretKey.algorithm)
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        return cipher.doFinal(data)
    }

    private fun getSecretKey(publicKey: ByteArray, privateKey: ByteArray): ByteArray {
        val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
        val publicKeySpec = X509EncodedKeySpec(publicKey)
        val dhPublicKey = keyFactory.generatePublic(publicKeySpec)
        val privateKeySpec = PKCS8EncodedKeySpec(privateKey)
        val dhPrivateKey = keyFactory.generatePrivate(privateKeySpec)
        val keyAgreement = KeyAgreement.getInstance(KEY_ALGORITHM, KEY_PROVIDER)
        keyAgreement.init(dhPrivateKey)
        keyAgreement.doPhase(dhPublicKey, true)
        return keyAgreement.generateSecret(SECRET_ALGORITHM).encoded
    }
}

private fun JsonNode.firstText(vararg fields: String): String =
    fields.asSequence()
        .map { path(it).asText("").trim() }
        .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        .orEmpty()

private fun Map<String, String>.pick(vararg keys: String): String? =
    keys.asSequence()
        .mapNotNull { this[it]?.trim() }
        .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

private fun Map<String, String>.requireAny(vararg keys: String, fieldName: String): String =
    pick(*keys) ?: throw PluginException("凭证字段为空: $fieldName")

private data class CredentialInfo(
    val credentialType: String,
    val v1: String,
    val v2: String,
    val v3: String,
    val v4: String,
)

private data class DHKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)

private object Log {
    fun info(message: String) = println("##[info]$message")
    fun warning(message: String) = println("##[warning]$message")
}

private val objectMapper = ObjectMapper()

private const val CLONE_MAX_ATTEMPTS = 3
