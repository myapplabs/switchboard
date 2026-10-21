package llc.applabs.design.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import llc.applabs.design.tokens.AppLabsSpacing
import llc.applabs.design.tokens.ShapeCard

/**
 * AppLabs card components.
 *
 * Three variants matching Material 3 card types, all using the AppLabs
 * shape and spacing tokens.
 */

// ---------------------------------------------------------------------------
// Filled card — default surface color, no border
// ---------------------------------------------------------------------------

@Composable
fun AppLabsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = ShapeCard,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(AppLabsSpacing.md)) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Elevated card — drop shadow, stands out from background
// ---------------------------------------------------------------------------

@Composable
fun AppLabsElevatedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = ShapeCard,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = AppLabsSpacing.xs),
    ) {
        Column(modifier = Modifier.padding(AppLabsSpacing.md)) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Outlined card — border, no elevation. Good for lists on white backgrounds.
// ---------------------------------------------------------------------------

@Composable
fun AppLabsOutlinedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = ShapeCard,
    ) {
        Column(modifier = Modifier.padding(AppLabsSpacing.md)) {
            content()
        }
    }
}
