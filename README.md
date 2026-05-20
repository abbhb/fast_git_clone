# fast-git-clone

蓝盾流水线 Git 加速克隆插件。

## 功能

- 支持按蓝盾代码库选择或按代码库别名输入，自动读取代码库自身绑定的授权信息。
- 保留自定义 Git 仓库 URL + 用户名 + Token 的原有使用方式。
- 配置 Git credential store，并写入 `code.cwoa.net` 访问凭证。
- 通过本地缓存目录复用 Git 仓库，减少重复全量 clone。
- 按输入分支强制同步远端分支内容。
- 使用 `rsync --delete` 将代码复制到目标目录，并排除 `.git`。
- 当目标目录与默认工作目录相同时拒绝执行，避免清空流水线工作目录。

## 代码库与凭证

`代码库来源` 支持三种模式：

- `按蓝盾代码库选择`：通过代码库选择器选择有 `USE` 权限的蓝盾代码库，运行时根据 `repositoryHashId` 查询代码库详情。
- `按蓝盾代码库别名输入`：运行时根据代码库别名查询蓝盾代码库详情。
- `按自定义仓库 URL 输入`：沿用原有的 Git 仓库地址、Git 用户名、Git Token、Git 域名参数。

选择蓝盾代码库时不会额外展示凭证输入项。运行时会参考 `ci-checkout` 的逻辑：如果代码库授权类型是 `OAUTH`，使用代码库授权人的 OAuth token；否则使用代码库详情中的 `credentialId` 调用蓝盾凭证服务读取凭证。日志只打印 `credentialId`、授权类型和代码库信息，不打印 token、密码或私钥。

## 打包

```bash
task package
```

执行后会在上级目录生成 `fast_git_clone.zip`，zip 根目录包含 `task.json` 和 `fast_git_clone.jar`，可直接上传插件市场。

构建过程通过 Docker 运行 Gradle，默认镜像为 `gradle:8.8-jdk17`，避免污染本机 Java/Kotlin/Gradle 环境。如需切换镜像：

```bash
GRADLE_IMAGE=gradle:8.10-jdk17 task package
```

发布到插件市场时上传 `task package` 生成的 zip。`task.json` 的 `execution` 使用 Java 插件形式：`packagePath` 指向 zip 内的 fat jar，`target` 通过 `$bk_java_path -Dfile.encoding=UTF-8 -jar fast_git_clone.jar` 执行，确保 JVM 标准输出使用 UTF-8，避免中文日志显示为问号。