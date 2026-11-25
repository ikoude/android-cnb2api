package com.ikoude.cnb2api.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCompatibilityTest {
    private fun fixture(): String =
        javaClass.classLoader!!.getResourceAsStream("plugin-enc.json")!!.bufferedReader().readText()

    @Test
    fun parsesPluginEncryptedBackup() {
        val parsed = Backup.parse(fixture(), "test123")
        assertEquals("settings", parsed.scope)
        assertEquals(0, parsed.accounts.size)
        val s = parsed.settings!!
        assertEquals(150, s.checkSeconds)
        assertEquals("加密插件备份", s.lowCreditMessage)
    }

    @Test
    fun mergeKeepsExistingRepoCaseWhenBackupDoesNotContainIt() {
        // 回归：现有账号不在导入的备份里时，仓库名大小写必须原样保留。
        val existing = listOf(AccountConfig(token = "t", repo = "GPU.3/api"))
        val incoming = listOf(AccountConfig(token = "t2", repo = "other/repo"))
        val merged = Backup.merge(existing, incoming)
        assertEquals("GPU.3/api", merged.accounts.first { it.token == "t" }.repo)
        assertEquals(1, merged.added)
        assertEquals(0, merged.replaced)
    }

    @Test
    fun plainRoundTripKeepsSettingsAndAccounts() {
        val accounts = listOf(AccountConfig(token = "t", repo = "Org/Repo", proxyKey = "sk-x"))
        val settings = AppSettings(models = "m1,m2", checkSeconds = 99, lowCreditMessage = "hi")
        val doc = Backup.export("all", accounts, settings, "")
        val parsed = Backup.parse(doc, "")
        assertEquals("all", parsed.scope)
        assertEquals(1, parsed.accounts.size)
        assertEquals("Org/Repo", parsed.accounts[0].repo)
        assertEquals(99, parsed.settings!!.checkSeconds)
        assertTrue(doc.contains("\"format\": \"cnb2api-backup\""))
    }
}
