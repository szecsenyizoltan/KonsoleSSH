package hu.billman.konsolessh

/** The app with network-free sessions ([FakeTerminalSession]). */
class TestKonsoleApplication : KonsoleApplication() {
    override fun createContainer() = AppContainer(
        this,
        sessionFactoryOverride = FakeTerminalSession.factory,
    )
}
