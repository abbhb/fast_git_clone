package com.tencent.bk.devops.atom.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RsyncCommandTest {
    @Test
    fun `builds rsync command with git directory excluded by default behavior`() {
        assertEquals(
            listOf("rsync", "-a", "--delete", "--exclude=.git", "/cache/", "/target/"),
            RsyncCommand.build("/cache", "/target", excludeGitDir = true),
        )
    }

    @Test
    fun `builds rsync command without git exclusion when disabled`() {
        assertEquals(
            listOf("rsync", "-a", "--delete", "/cache/", "/target/"),
            RsyncCommand.build("/cache", "/target", excludeGitDir = false),
        )
    }

    @Test
    fun `parses dynamic boolean input values`() {
        assertTrue(BooleanParam.parse("true", defaultValue = false, key = "EXCLUDE_GIT_DIR"))
        assertTrue(BooleanParam.parse("1", defaultValue = false, key = "EXCLUDE_GIT_DIR"))
        assertTrue(BooleanParam.parse("yes", defaultValue = false, key = "EXCLUDE_GIT_DIR"))
        assertTrue(BooleanParam.parse("ON", defaultValue = false, key = "EXCLUDE_GIT_DIR"))

        assertFalse(BooleanParam.parse("false", defaultValue = true, key = "EXCLUDE_GIT_DIR"))
        assertFalse(BooleanParam.parse("0", defaultValue = true, key = "EXCLUDE_GIT_DIR"))
        assertFalse(BooleanParam.parse("no", defaultValue = true, key = "EXCLUDE_GIT_DIR"))
        assertFalse(BooleanParam.parse("off", defaultValue = true, key = "EXCLUDE_GIT_DIR"))
    }

    @Test
    fun `parses negated dynamic boolean input values`() {
        assertFalse(BooleanParam.parse("!true", defaultValue = true, key = "EXCLUDE_GIT_DIR"))
        assertFalse(BooleanParam.parse("! yes", defaultValue = true, key = "EXCLUDE_GIT_DIR"))
        assertFalse(BooleanParam.parse("!1", defaultValue = true, key = "EXCLUDE_GIT_DIR"))

        assertTrue(BooleanParam.parse("!false", defaultValue = false, key = "EXCLUDE_GIT_DIR"))
        assertTrue(BooleanParam.parse("! no", defaultValue = false, key = "EXCLUDE_GIT_DIR"))
        assertTrue(BooleanParam.parse("!0", defaultValue = false, key = "EXCLUDE_GIT_DIR"))
    }

    @Test
    fun `blank dynamic boolean input uses default value`() {
        assertTrue(BooleanParam.parse("", defaultValue = true, key = "EXCLUDE_GIT_DIR"))
        assertFalse(BooleanParam.parse(" ", defaultValue = false, key = "EXCLUDE_GIT_DIR"))
    }

    @Test
    fun `invalid dynamic boolean input fails fast`() {
        assertFailsWith<PluginException> {
            BooleanParam.parse("maybe", defaultValue = true, key = "EXCLUDE_GIT_DIR")
        }
    }

    @Test
    fun `builds source material repository name from url`() {
        assertEquals("rd-fy22-canway-kingeye/kingeye", repositoryNameFromUrl("https://code.cwoa.net/rd-fy22-canway-kingeye/kingeye.git"))
        assertEquals("group/repo", repositoryNameFromUrl("git@github.com:group/repo.git"))
    }

    @Test
    fun `builds source material commit urls`() {
        assertEquals(
            "https://code.cwoa.net/group/repo/-/commit/60466559",
            buildCommitUrl("https://code.cwoa.net/group/repo.git", "60466559", "CODE_GITLAB"),
        )
        assertEquals(
            "https://github.com/group/repo/commit/60466559",
            buildCommitUrl("git@github.com:group/repo.git", "60466559", "GITHUB"),
        )
    }

    @Test
    fun `normalizes source material scm types`() {
        assertEquals("GITHUB", inferScmType("https://github.com/group/repo.git"))
        assertEquals("CODE_TGIT", inferScmType("git@git.code.tencent.com:group/repo.git"))
        assertEquals("CODE_GITLAB", inferScmType("https://code.cwoa.net/group/repo.git"))

        assertEquals("SCM_GIT", normalizeScmType("SCM_GIT"))
        assertEquals("CODE_GIT", normalizeScmType("CODE_GIT"))
        assertEquals("CODE_GITLAB", normalizeScmType("com.tencent.CodeGitlabRepository"))
    }
}