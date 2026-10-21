package llc.applabs.design.tokens

import androidx.compose.ui.unit.dp

/**
 * AppLabs spacing scale.
 *
 * Use these instead of hardcoded dp values. Every spacing decision
 * in the app should map to one of these named steps.
 */
object AppLabsSpacing {
    /** 2dp — hairline separators, tight icon padding */
    val xxs = 2.dp
    /** 4dp — between icon and label, badge padding */
    val xs = 4.dp
    /** 8dp — internal component padding, between list item elements */
    val sm = 8.dp
    /** 12dp — secondary gaps, button vertical padding */
    val md_sm = 12.dp
    /** 16dp — standard content padding, card internal padding */
    val md = 16.dp
    /** 20dp — comfortable row height padding */
    val md_lg = 20.dp
    /** 24dp — section spacing, between card groups */
    val lg = 24.dp
    /** 32dp — major section separation */
    val xl = 32.dp
    /** 48dp — hero areas, empty state padding */
    val xxl = 48.dp
    /** 64dp — full-screen vertical centering offset */
    val xxxl = 64.dp
}
