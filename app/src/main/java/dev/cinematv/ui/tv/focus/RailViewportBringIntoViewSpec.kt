package dev.cinematv.ui.tv.focus

import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * Reveal the complete padded rail with Foundation's minimum-distance policy.
 *
 * A focused card and its containing padded row can request the vertical viewport
 * together. Once the row is visible, every contained card must also be satisfied.
 * TV's leading-edge pivot does not have that property: the two different leading
 * edges cannot both reach it. With integer lazy placement and a fractional pivot,
 * Foundation 1.10.6 can keep an otherwise visible request alive indefinitely.
 *
 * Apply only to Home/Library's vertical viewport; their item content restores the
 * inherited policy so horizontal TV focus scrolling retains its native pivot.
 */
internal object RailViewportBringIntoViewSpec : BringIntoViewSpec
