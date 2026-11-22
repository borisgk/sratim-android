package com.ru9n.sratim.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.ru9n.sratim.core.ui.theme.Pink80
import com.ru9n.sratim.core.ui.theme.Purple80
import com.ru9n.sratim.core.ui.theme.PurpleGrey80

@Composable
fun SratimTheme(
    content: @Composable () -> Unit,
) {
    val colorScheme = darkColorScheme(
        primary = Purple80,
        secondary = PurpleGrey80,
        tertiary = Pink80,
        background = Color.Black,
        surface = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
