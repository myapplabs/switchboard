package llc.applabs.design.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import llc.applabs.design.tokens.AppLabsSpacing
import llc.applabs.design.tokens.ErrorRed
import llc.applabs.design.tokens.Neutral99
import llc.applabs.design.tokens.ShapeButton

/**
 * AppLabs button variants.
 *
 * Always use one of these instead of Material [Button] directly.
 * This ensures consistent shape, spacing, and disabled states across
 * all screens.
 */

// ---------------------------------------------------------------------------
// Primary — filled, high emphasis (main actions)
// ---------------------------------------------------------------------------

@Composable
fun AppLabsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled && !isLoading,
        shape = ShapeButton,
        contentPadding = PaddingValues(horizontal = AppLabsSpacing.lg, vertical = AppLabsSpacing.md_sm),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Neutral99,
            )
        } else {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(AppLabsSpacing.sm))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// Full-width variant
@Composable
fun AppLabsPrimaryButtonFull(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    AppLabsPrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        isLoading = isLoading,
    )
}

// ---------------------------------------------------------------------------
// Secondary — outlined, medium emphasis
// ---------------------------------------------------------------------------

@Composable
fun AppLabsSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = ShapeButton,
        contentPadding = PaddingValues(horizontal = AppLabsSpacing.lg, vertical = AppLabsSpacing.md_sm),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(AppLabsSpacing.sm))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------------------
// Destructive — filled red, for irreversible actions
// ---------------------------------------------------------------------------

@Composable
fun AppLabsDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled && !isLoading,
        shape = ShapeButton,
        colors = ButtonDefaults.buttonColors(
            containerColor = ErrorRed,
            contentColor = Neutral99,
        ),
        contentPadding = PaddingValues(horizontal = AppLabsSpacing.lg, vertical = AppLabsSpacing.md_sm),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Neutral99,
            )
        } else {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---------------------------------------------------------------------------
// Text — no background, low emphasis (inline actions, cancel)
// ---------------------------------------------------------------------------

@Composable
fun AppLabsTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ShapeButton,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
