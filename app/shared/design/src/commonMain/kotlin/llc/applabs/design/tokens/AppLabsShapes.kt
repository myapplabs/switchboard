package llc.applabs.design.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * AppLabs shape scale.
 *
 * Facebook uses relatively tight corner radii on cards and buttons —
 * rounded but not pill-shaped. We mirror that feel.
 */
val AppLabsShapes = Shapes(
    /** Chips, badges, small tags */
    extraSmall = RoundedCornerShape(4.dp),
    /** Text fields, small cards */
    small = RoundedCornerShape(8.dp),
    /** Standard cards, dialogs, bottom sheets */
    medium = RoundedCornerShape(12.dp),
    /** Large modals, full-width cards */
    large = RoundedCornerShape(16.dp),
    /** Floating action buttons, image containers */
    extraLarge = RoundedCornerShape(28.dp),
)

// Named aliases for direct use in components
val ShapeButton    = RoundedCornerShape(8.dp)
val ShapeCard      = RoundedCornerShape(12.dp)
val ShapeChip      = RoundedCornerShape(4.dp)
val ShapeTextField = RoundedCornerShape(8.dp)
val ShapeDialog    = RoundedCornerShape(16.dp)
val ShapeBottomSheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
val ShapePill      = RoundedCornerShape(50)
