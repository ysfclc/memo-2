package app.memo.runtime

import app.memo.platform.android.vault.BackupRotationPolicy
import app.memo.platform.android.vault.VaultDataPolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JVM-only checks for the storage safety rules that do not need Android. */
class RuntimeSafetyTest {

    @Test
    fun leftoverTempFilesDoNotCountAsVaultData() {
        assertFalse(VaultDataPolicy.countsAsVaultData("workspace.syn.tmp"))
        assertFalse(VaultDataPolicy.countsAsVaultData("runs.json.tmp"))
        assertTrue(VaultDataPolicy.countsAsVaultData("workspace.syn"))
        assertTrue(VaultDataPolicy.countsAsVaultData("workspace.syn.bak1"))
        assertTrue(VaultDataPolicy.countsAsVaultData("migration.v1"))
    }


    private val last = 1_700_000_000_000L

    @Test
    fun rotationIsDueWhenNoRotationIsKnown() {
        assertTrue(BackupRotationPolicy.isDue(last, 0L))
    }

    @Test
    fun rotationIsNotDueWithinTenMinutes() {
        assertFalse(BackupRotationPolicy.isDue(last + 1_000L, last))
        assertFalse(BackupRotationPolicy.isDue(last + BackupRotationPolicy.INTERVAL_MS - 1L, last))
    }

    @Test
    fun rotationIsDueAfterTenMinutes() {
        assertTrue(BackupRotationPolicy.isDue(last + BackupRotationPolicy.INTERVAL_MS, last))
        assertTrue(BackupRotationPolicy.isDue(last + 60 * 60 * 1000L, last))
    }

    @Test
    fun intervalIsTenMinutes() {
        assertTrue(BackupRotationPolicy.INTERVAL_MS == 10 * 60 * 1000L)
    }

    @Test
    fun clockGoingBackwardsRotates() {
        assertTrue(BackupRotationPolicy.isDue(last - 5_000L, last))
    }
}
