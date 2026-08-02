package com.ru9n.sratim.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.ru9n.sratim.core.ui.theme.Pink40
import com.ru9n.sratim.core.ui.theme.Pink80
import com.ru9n.sratim.core.ui.theme.Purple40
import com.ru9n.sratim.core.ui.theme.Purple80
import com.ru9n.sratim.core.ui.theme.PurpleGrey40
import com.ru9n.sratim.core.ui.theme.PurpleGrey80

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SratimTheme(
    isInDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (isInDarkTheme) {
        darkColorScheme(
            primary = Purple80,
            secondary = PurpleGrey80,
            tertiary = Pink80
        )
    } else {
        lightColorScheme(
            primary = Purple40,
            secondary = PurpleGrey40,
            tertiary = Pink40
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}