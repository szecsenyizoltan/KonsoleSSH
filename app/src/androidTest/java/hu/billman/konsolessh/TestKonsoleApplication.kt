package hu.billman.konsolessh

import hu.billman.konsolessh.diagnostics.ProblemReports
import java.io.File

/**
 * Az app hálózat nélküli munkamenetekkel ([FakeTerminalSession]) és hibajelentés
 * nélkül, hogy egy korábbi futás ANR-ablaka ne takarja el a tesztelt képernyőt.
 */
class TestKonsoleApplication : KonsoleApplication() {
    override fun createContainer() = AppContainer(
        this,
        sessionFactoryOverride = FakeTerminalSession.factory,
        problemReportsOverride = object : ProblemReports {
            override suspend fun collectNew(): File? = null
            override suspend fun latest(): File? = null
            override suspend fun markSeen() = Unit
        },
    )
}
