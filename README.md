# fast-git-clone

蓝盾流水线 Git 加速克隆插件。

## 功能

- 配置 Git credential store，并写入 `code.cwoa.net` 访问凭证。
- 通过本地缓存目录复用 Git 仓库，减少重复全量 clone。
- 按输入分支强制同步远端分支内容。
- 使用 `rsync --delete` 将代码复制到目标目录，并排除 `.git`。
- 当目标目录与默认工作目录相同时拒绝执行，避免清空流水线工作目录。

## 打包

```bash
task package
```

执行后会在上级目录生成 `fast_git_clone.zip`，zip 根目录包含 `task.json` 和 `fast_git_clone.jar`，可直接上传插件市场。

构建过程通过 Docker 运行 Gradle，默认镜像为 `gradle:8.8-jdk17`，避免污染本机 Java/Kotlin/Gradle 环境。如需切换镜像：

```bash
GRADLE_IMAGE=gradle:8.10-jdk17 task package
```

发布到插件市场时上传 `task package` 生成的 zip。`task.json` 的 `execution` 使用 Java 插件形式：`packagePath` 指向 zip 内的 fat jar，`target` 通过 `$bk_java_path -jar fast_git_clone.jar -Dfile.encoding=utf8` 执行。