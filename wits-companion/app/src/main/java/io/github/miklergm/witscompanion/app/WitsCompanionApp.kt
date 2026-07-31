package io.github.miklergm.witscompanion.app

import android.app.Application
import io.github.miklergm.witscompanion.carstate.CarStateRepository
import io.github.miklergm.witscompanion.carstate.PropertyReader
import io.github.miklergm.witscompanion.layout.LayoutEngine
import io.github.miklergm.witscompanion.layout.LayoutRecoveryCoordinator
import io.github.miklergm.witscompanion.layout.LayoutRepository
import io.github.miklergm.witscompanion.logging.EventLogger
import io.github.miklergm.witscompanion.media.MediaSessionRepository
import io.github.miklergm.witscompanion.safety.ActionRateLimiter
import io.github.miklergm.witscompanion.safety.ReverseGuard
import io.github.miklergm.witscompanion.safety.SourceGuard
import io.github.miklergm.witscompanion.signalexplorer.SignalExplorer
import io.github.miklergm.witscompanion.wits.WitsNightModeController
import io.github.miklergm.witscompanion.wits.WitsSourceController
import io.github.miklergm.witscompanion.wits.WitsWindowController

/**
 * Manual dependency graph. No DI framework — the object count does not justify one,
 * and fewer dependencies means a simpler offline build.
 */
class WitsCompanionApp : Application() {

    lateinit var eventLogger: EventLogger
        private set
    lateinit var propertyReader: PropertyReader
        private set
    lateinit var carStateRepository: CarStateRepository
        private set
    lateinit var layoutRepository: LayoutRepository
        private set
    lateinit var windowController: WitsWindowController
        private set
    lateinit var sourceController: WitsSourceController
        private set
    lateinit var nightModeController: WitsNightModeController
        private set
    lateinit var layoutEngine: LayoutEngine
        private set
    lateinit var recoveryCoordinator: LayoutRecoveryCoordinator
        private set
    lateinit var mediaRepository: MediaSessionRepository
        private set
    lateinit var reverseGuard: ReverseGuard
        private set
    lateinit var rateLimiter: ActionRateLimiter
        private set
    lateinit var signalExplorer: SignalExplorer
        private set

    override fun onCreate() {
        super.onCreate()

        eventLogger = EventLogger(this)
        propertyReader = PropertyReader()
        layoutRepository = LayoutRepository(this)

        reverseGuard = ReverseGuard()
        rateLimiter = ActionRateLimiter()
        val sourceGuard = SourceGuard(reverseGuard)

        carStateRepository = CarStateRepository(
            appContext = this,
            propertyReader = propertyReader,
            logger = eventLogger,
        )

        windowController = WitsWindowController(this, eventLogger)

        layoutEngine = LayoutEngine(
            appContext = this,
            windowController = windowController,
            reverseGuard = reverseGuard,
            rateLimiter = rateLimiter,
            logger = eventLogger,
        )

        sourceController = WitsSourceController(
            appContext = this,
            sourceGuard = sourceGuard,
            rateLimiter = rateLimiter,
            logger = eventLogger,
            onBeforeSwitch = { layoutEngine.cancelPending() },
        )
        nightModeController = WitsNightModeController(this, rateLimiter, eventLogger)

        recoveryCoordinator = LayoutRecoveryCoordinator(
            repository = layoutRepository,
            engine = layoutEngine,
            reverseGuard = reverseGuard,
            logger = eventLogger,
        )

        mediaRepository = MediaSessionRepository(this)

        signalExplorer = SignalExplorer(
            appContext = this,
            carStateRepository = carStateRepository,
            propertyReader = propertyReader,
            logger = eventLogger,
        )

        carStateRepository.addObserver(recoveryCoordinator)
        carStateRepository.start()
        if (layoutRepository.simulationEnabled) {
            carStateRepository.setSimulationEnabled(true)
        }

        eventLogger.log(
            category = "app", action = "start",
            extras = mapOf(
                "version" to io.github.miklergm.witscompanion.BuildConfig.VERSION_NAME,
                "property_strategy" to propertyReader.activeStrategy.name,
            ),
        )
    }
}
