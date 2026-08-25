package com.freeturn.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.freeturn.app.R
import com.freeturn.app.ui.theme.Spacing

/**
 * Тональная карточка предупреждения (tertiaryContainer): то, что надо учесть, но не ошибка.
 * Отличима от соседних абзацев supporting-текста; для ошибок - [InlineErrorCard].
 */
@Composable
fun InlineNoticeCard(message: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(Spacing.md)) {
            Icon(
                painterResource(R.drawable.info_24px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                message,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
