package me.rerere.androidcontrol.shell

import org.junit.Assert.*
import org.junit.Test

class OwnedTaskCleanupTest {
    private val owned = OwnedTaskSnapshot(31, 9, setOf("fixture.game"))
    private val launched = setOf("fixture.game")

    @Test fun onlyTaskStillOnOwnedDisplayCanBeFinishedBeforeDisplayTeardown() {
        assertTrue(ownedTaskCanBeRemoved(9, launched, owned, owned))
        assertFalse(ownedTaskCanBeRemoved(0, launched, owned.copy(displayId=0), owned.copy(displayId=0)))
        assertFalse(ownedTaskCanBeRemoved(8, launched, owned, owned))
        assertFalse(ownedTaskCanBeRemoved(9, setOf("other.app"), owned, owned))
    }

    @Test fun takeoverOrTaskIdReuseBetweenSelectionAndRemovalCannotCloseUserApp() {
        assertFalse(ownedTaskCanBeRemoved(9, launched, owned, owned.copy(displayId=0)))
        assertFalse(ownedTaskCanBeRemoved(9, launched, owned, owned.copy(displayId=12)))
        assertFalse(ownedTaskCanBeRemoved(9, launched, owned, owned.copy(packages=setOf("other.app"))))
        assertFalse(ownedTaskCanBeRemoved(9, launched, owned, null))
    }

    @Test fun anotherTaskOrPackageOutsideTheLaunchSetIsNotAValidCleanupTarget() {
        assertFalse(ownedTaskCanBeRemoved(9, launched, owned, owned.copy(id=32)))
        val foreign = OwnedTaskSnapshot(34, 9, setOf("unrelated.app"))
        assertFalse(ownedTaskCanBeRemoved(9, launched, foreign, foreign))
    }
}
