package com.tencent.bk.devops.atom.task

import com.fasterxml.jackson.annotation.JsonProperty
import com.tencent.bk.devops.atom.pojo.AtomBaseParam

class FastGitCloneParam : AtomBaseParam() {
    @JsonProperty("repositoryType")
    var repositoryType: String = "URL"

    @JsonProperty("repositoryHashId")
    var repositoryHashId: String = ""

    @JsonProperty("repositoryName")
    var repositoryName: String = ""

    @JsonProperty("GIT_USERNAME")
    var gitUsername: String = ""

    @JsonProperty("GIT_TOKEN")
    var gitToken: String = ""

    @JsonProperty("GIT_HOST")
    var gitHost: String = ""

    @JsonProperty("KINGEYE_GIT_REPO")
    var kingeyeGitRepo: String = ""

    @JsonProperty("BRANCH")
    var branch: String = ""

    @JsonProperty("CACHE_DIR")
    var cacheDir: String = ""

    @JsonProperty("TARGET_DIR")
    var targetDir: String = ""

    @JsonProperty("EXCLUDE_GIT_DIR")
    var excludeGitDir: String = "true"

    @JsonProperty("DEFAULT_WORK_DIR")
    var defaultWorkDir: String = ""
}
