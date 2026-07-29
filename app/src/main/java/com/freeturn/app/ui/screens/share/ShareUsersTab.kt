@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.freeturn.app.ui.screens.share

import android.text.format.DateUtils
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.data.share.SharedClient
import com.freeturn.app.data.share.WgPeer
import com.freeturn.app.ui.components.EmptyState
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.components.SettingsRowIcon
import com.freeturn.app.ui.theme.LocalReducedMotion
import com.freeturn.app.ui.theme.extendedColorScheme
import com.freeturn.app.viewmodel.share.ShareUiState
import com.freeturn.app.ui.theme.Spacing

/** Пир считается онлайн, если хендшейк был не позже 3 минут назад (WG re-key ~2 мин). */
private const val ONLINE_WINDOW_SEC = 180L

@Composable
fun ShareUsersTab(
    state: ShareUiState,
    onSelectServer: (String) -> Unit,
    onRefresh: () -> Unit,
    onReshare: (WgPeer) -> Unit,
    onRevoke: (WgPeer) -> Unit,
    onReshareClient: (SharedClient) -> Unit,
    onRevokeClient: (SharedClient) -> Unit
) {
    // Тикает, чтобы online-статус пира гас сам по времени, а не только при перезагрузке.
    val nowSec by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            value = System.currentTimeMillis() / 1000
            delay(30_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        ServerSelector(
            servers = state.servers,
            selected = state.selectedServer,
            onSelect = onSelectServer
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel(stringResource(R.string.share_users_section))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.peersLoading) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            painterResource(R.drawable.refresh_24px),
                            contentDescription = stringResource(R.string.share_users_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        state.peersError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        val total = state.peers.size + state.clients.size
        if (state.peersLoaded && total == 0) {
            EmptyState(
                iconRes = R.drawable.group_off_24px,
                desc = stringResource(R.string.share_users_empty),
                modifier = Modifier.fillMaxWidth()
            )
        } else if (total > 0) {
            SettingsGroup {
                state.peers.forEachIndexed { index, peer ->
                    SettingsGroupItem(index, total) {
                        UserRow(
                            name = when {
                                peer.isSelf -> stringResource(R.string.share_peer_self)
                                peer.name.isNotEmpty() -> peer.name
                                else -> stringResource(R.string.share_peer_unnamed)
                            },
                            subtitle = peerSubtitle(peer),
                            online = peer.lastHandshakeEpoch?.let {
                                nowSec - it < ONLINE_WINDOW_SEC
                            } ?: false,
                            resharing = state.resharePubkey == peer.pubkey,
                            canReshare = peer.hasStoredConf,
                            canRevoke = !peer.isSelf,
                            onReshare = { onReshare(peer) },
                            onRevoke = { onRevoke(peer) }
                        )
                    }
                }
                state.clients.forEachIndexed { index, client ->
                    SettingsGroupItem(state.peers.size + index, total) {
                        UserRow(
                            name = client.name.ifEmpty { stringResource(R.string.share_peer_unnamed) },
                            subtitle = stringResource(R.string.share_client_proxy_access),
                            // Прокси-гость без WG-пира - статус подключения серверу неизвестен.
                            online = false,
                            resharing = false,
                            canReshare = true,
                            canRevoke = true,
                            onReshare = { onReshareClient(client) },
                            onRevoke = { onRevokeClient(client) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserRow(
    name: String,
    subtitle: String,
    online: Boolean,
    resharing: Boolean,
    canReshare: Boolean,
    canRevoke: Boolean,
    onReshare: () -> Unit,
    onRevoke: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.md, bottom = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Box {
            SettingsRowIcon(R.drawable.person_24px)
            if (online) OnlineDot(modifier = Modifier.align(Alignment.BottomEnd))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            resharing -> Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator(modifier = Modifier.size(24.dp))
            }
            canReshare || canRevoke -> UserRowMenu(
                canReshare = canReshare,
                canRevoke = canRevoke,
                onReshare = onReshare,
                onRevoke = onRevoke
            )
        }
    }
}

@Composable
private fun UserRowMenu(
    canReshare: Boolean,
    canRevoke: Boolean,
    onReshare: () -> Unit,
    onRevoke: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painterResource(R.drawable.more_vert_24px),
                contentDescription = stringResource(R.string.share_peer_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (canReshare) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share_peer_reshare)) },
                    onClick = {
                        expanded = false
                        onReshare()
                    },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.share_24px), contentDescription = null)
                    }
                )
            }
            if (canRevoke) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.share_peer_revoke),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        expanded = false
                        onRevoke()
                    },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.person_remove_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun OnlineDot(modifier: Modifier = Modifier) {
    val pulse: State<Float> = if (LocalReducedMotion.current) {
        remember { mutableFloatStateOf(1f) }
    } else {
        rememberInfiniteTransition(label = "online").animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "online_alpha"
        )
    }
    Box(
        modifier = modifier
            .size(13.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .graphicsLayer { alpha = pulse.value }
                .background(MaterialTheme.extendedColorScheme.success, CircleShape)
        )
    }
}

@Composable
private fun peerSubtitle(peer: WgPeer): String {
    val context = LocalContext.current
    return peer.lastHandshakeEpoch?.let { epoch ->
        stringResource(
            R.string.share_peer_seen,
            DateUtils.formatDateTime(
                context,
                epoch * 1000L,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
            )
        )
    } ?: stringResource(R.string.share_peer_never_seen)
}
