package app.tropa

import android.app.Application
import app.tropa.ui.Controller

class TropaApp : Application() {
    lateinit var store: app.tropa.data.Store
        private set
    lateinit var controller: Controller
        private set

    override fun onCreate() {
        super.onCreate()
        store = app.tropa.data.Store(this)
        controller = Controller(this, store)
        app.tropa.widget.WidgetUpdater.start(this)
    }
}
