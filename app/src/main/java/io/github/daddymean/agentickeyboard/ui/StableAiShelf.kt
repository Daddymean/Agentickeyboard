package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Automatic AI updates share one height; only explicit expansion can move keys. */
@Composable
internal fun StableAiShelf(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val height = LocalKeyboardMetrics.current.shelfHeight
    Box(
        modifier = modifier.fillMaxWidth().then(
            if (expanded) Modifier.heightIn(min = height) else Modifier.height(height)
        ),
        contentAlignment = Alignment.CenterStart,
        content = content
    )
}
