package io.github.miklergm.witscompanion.ui

/**
 * Sizes for the Cockpit's transport row.
 *
 * Named and shared so the arrangement can be measured in a test, because the thing that was
 * wrong with it was arithmetic rather than appearance.
 *
 * The row centres its children, and the save-to-collection button is only present for players
 * that offer one. Toggling that button between GONE and VISIBLE therefore re-centred the whole
 * group and moved play sideways by half a slot — 39dp — depending on which app was playing.
 * The comment above the row already said the three transport controls must not shift; centring
 * the row defeated it anyway.
 *
 * So both flanks are reserved. An empty leading slot balances the optional trailing one, the
 * optional button is INVISIBLE rather than GONE when unavailable, and play sits on the row's
 * centre line whether there are three buttons or four. In a car you reach for it without
 * looking, so its position must not depend on the player.
 */
object TransportMetrics {

    /** A skip button: prev, next, and any optional action. */
    const val BUTTON_DP = 54

    /** Play/pause, larger because it is the one you aim for. */
    const val EMPHASISED_DP = 66

    /** Margin on each side of every button. */
    const val MARGIN_DP = 12

    /** Width one flank occupies, button plus both margins. */
    const val SLOT_DP = BUTTON_DP + MARGIN_DP * 2
}
