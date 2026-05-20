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
}