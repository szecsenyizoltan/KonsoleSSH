package hu.billman.konsolessh

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Az instrumentált tesztek a [TestKonsoleApplication]-nel futnak. */
class KonsoleTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestKonsoleApplication::class.java.name, context)
}
