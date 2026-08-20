package io.github.miklergm.witscompanion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.miklergm.witscompanion.app.WitsCompanionApp
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.carstate.CarStateRepository
import io.github.miklergm.witscompanion.media.MediaSessionRepository
import io.github.miklergm.witscompanion.media.MediaSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Publishes the Cockpit panel's state, and owns the subscriptions that keep it current.
 *
 * Deliberately thin. Every decision lives in [CockpitState] as a pure function; this class
 * subscribes to the repositories, calls them, and pushes the result into a [StateFlow]. The
 * split is what makes the panel's behaviour testable without an Activity — see
 * `CockpitStateTest`, which exercises the derivations directly.
 *
 * Surviving configuration change is the other half of the point. The panel is recreated on
 * every day/night flip and on the freeform resizes the Cockpit performs constantly; holding
 * this state in the Activity meant rebuilding it from scratch each time, and it was that
 * rebuild losing the floating package that once made the rail highlight go blank until the
 * user tapped something.
 */
class CockpitViewModel(
    private val app: WitsCompanionApp,
) : ViewModel(), CarStateRepository.Observer, MediaSessionRepository.Listener {

    private val _state = MutableStateFlow(CockpitUiState())
    val state: StateFlow<CockpitUiState> = _state.asStateFlow()

    @Volatile
    private var carState: CarState = CarState()

    @Volatile
    private var media: MediaSnapshot? = null

    /** True while automatic actions are permissible; the guard still has the final say. */
    val automationPermitted: Boolean get() = CockpitState.automationPermitted(carState)

    init {
        app.carStateRepository.addObserver(this)
        app.mediaRepository.addListener(this)
        refresh()
    }

    override fun onCleared() {
        app.carStateRepository.removeObserver(this)
        app.mediaRepository.removeListener(this)
        super.onCleared()
    }

    override fun onCarState(state: CarState) {
        carState = state
        refresh()
    }

    override fun onMedia(snapshot: MediaSnapshot) {
        media = snapshot
        refresh()
    }

    /**
     * Recomputes the published state.
     *
     * @param fillsDisplay whether the panel currently occupies the whole display. Only the
     *   Activity can answer that — it is a property of the window, not of any repository —
     *   so it is passed in rather than read here.
     */
    fun refresh(fillsDisplay: Boolean = _state.value.reservation != null) {
        val repository = app.layoutRepository
        val catalog = app.appCatalog
        val defaults = catalog.vendorDefaults()

        _state.update {
            CockpitUiState(
                statusText = CockpitState.statusText(carState),
                floatingPackage = CockpitState.floatingPackage(
                    cockpitLeft = repository.cockpitLeft,
                    lastAppliedPreset = repository.lastAppliedPreset(),
                    defaultAnchored = repository.preset(
                        io.github.miklergm.witscompanion.layout.DefaultPresets.ID_MAPS_ANCHORED
                    ),
                ),
                railPackages = CockpitState.railPackages(
                    vendorNavigation = defaults.navigation,
                    vendorMusic = defaults.music,
                    vendorVideo = defaults.video,
                    isLaunchable = app.windowController::isLaunchable,
                ),
                settingsTileSelected =
                    repository.cockpitLeft == io.github.miklergm.witscompanion.layout.CockpitLeft.Config,
                media = CockpitState.media(media),
                hotspot = CockpitState.hotspot(
                    supported = app.hotspotController.isSupported(),
                    state = app.hotspotController.state(),
                ),
                brightnessPercent = app.brightnessController.percent(),
                reservation = CockpitState.reservation(
                    fillsDisplay = fillsDisplay,
                    split = repository.split,
                    swapped = repository.swapped,
                ),
            )
        }
    }

    /**
     * ViewModels are constructed by the framework, so the application handle arrives through a
     * factory rather than a constructor call.
     */
    class Factory(private val app: WitsCompanionApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CockpitViewModel::class.java)) {
                "unexpected ViewModel: ${modelClass.name}"
            }
            return CockpitViewModel(app) as T
        }
    }
}
