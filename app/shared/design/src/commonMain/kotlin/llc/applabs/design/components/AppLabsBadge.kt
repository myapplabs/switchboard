package llc.applabs.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import llc.applabs.design.tokens.AppLabsSpacing
import llc.applabs.design.tokens.Blue40
import llc.applabs.design.tokens.Neutral99
import llc.applabs.design.tokens.ShapePill
import llc.applabs.design.tokens.SuccessGreen

/**
 * AppLabs badge / status pill.
 *
 * Used for Pro status, feature labels, small status indicators.
 */

enum class AppLabsBadgeStyle {
    Primary,    // Blue — default
    Success,    // Green
    Neutral,    // Gray
    Custom,
}

@Composable
fun AppLabsBadge(
    text: String,
    modifier: Modifier = Modifier,
    style: AppLabsBadgeStyle = AppLabsBadgeStyle.Primary,
    customContainerColor: Color = Blue40,
    customContentColor: Color = Neutral99,
) {
    val containerColor = when (style) {
        AppLabsBadgeStyle.Primary -> MaterialTheme.colorScheme.primary
        AppLabsBadgeStyle.Success -> SuccessGreen
        AppLabsBadgeStyle.Neutral -> MaterialTheme.colorScheme.surfaceVariant
        AppLabsBadgeStyle.Custom  -> customContainerColor
    }

    val contentColor = when (style) {
        AppLabsBadgeStyle.Primary -> MaterialTheme.colorScheme.onPrimary
        AppLabsBadgeStyle.Success -> Neutral99
        AppLabsBadgeStyle.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        AppLabsBadgeStyle.Custom  -> customContentColor
    }

    Box(
        modifier = modifier
            .background(color = containerColor, shape = ShapePill)
            .padding(horizontal = AppLabsSpacing.sm, vertical = AppLabsSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
