package com.compass.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// M3 shape scale: extraSmall=4, small=8, medium=12, large=16, extraLarge=28
object CompassShapes {
    val Button: Shape = CircleShape
    val ButtonPressed: Shape = RoundedCornerShape(18.dp)
    val Card: Shape = RoundedCornerShape(28.dp)
    val CardPressed: Shape = RoundedCornerShape(20.dp)
    val Pill: Shape = CircleShape
    val PillPressed: Shape = RoundedCornerShape(16.dp)
}
