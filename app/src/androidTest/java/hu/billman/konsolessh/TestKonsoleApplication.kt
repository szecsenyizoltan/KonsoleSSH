package hu.billman.konsolessh

import hu.billman.konsolessh.diagnostics.ProblemReports
import java.io.File

/**
 * The app with network-free sessions ([FakeTerminalSession]) and without
 * problem reports, so an earlier run's ANR dialog cannot cover the screen
 * under test.
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
