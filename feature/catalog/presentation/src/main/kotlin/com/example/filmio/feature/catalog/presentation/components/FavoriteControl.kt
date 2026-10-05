package com.example.filmio.feature.catalog.presentation.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.filmio.feature.catalog.presentation.R

@Composable
internal fun FavoriteControl(
    title: String,
    favorite: Boolean?,
    enabled: Boolean,
    onSetFavorite: (Boolean) -> Unit,
    compact: Boolean = false
) {
    val selected = favorite ?: false
    val description = stringResource(if (selected) R.string.remove_saved_movie else R.string.save_movie, title)
    val status = stringResource(
        when {
            favorite == null -> R.string.favorite_status_unknown
            selected -> R.string.saved
            else -> R.string.save
        }
    )
    val modifier = Modifier
        .heightIn(min = 48.dp)
        .semantics {
            this.selected = selected
            contentDescription = description
            stateDescription = status
        }
    val active = enabled && favorite != null
    if (compact) {
        IconToggleButton(
            checked = selected, onCheckedChange = onSetFavorite, enabled = active,
            modifier = modifier.size(48.dp), colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                disabledContentColor = MaterialTheme.colorScheme.primary,
            )
        ) {
            FavoriteIcon(selected)
        }
    } else {
        FilledTonalButton(
            onClick = { onSetFavorite(!selected) }, enabled = active, modifier = modifier,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        ) {
            FavoriteIcon(selected)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (selected) R.string.saved else R.string.save))
        }
    }
}

@Composable
private fun FavoriteIcon(selected: Boolean) {
    Icon(
        painterResource(if (selected) R.drawable.ic_bookmark_check else R.drawable.ic_bookmark),
        contentDescription = null, modifier = Modifier.size(20.dp)
    )
}
