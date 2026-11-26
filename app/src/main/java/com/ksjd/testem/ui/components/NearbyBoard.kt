package com.ksjd.testem.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ksjd.testem.R

/**
 * 2.19.0: a phone without data heard a nearby phone's stop packet (emHUB-signed): that stop's next departures with their
 * delays, and its notices, on the home screen, so someone at a stop without data sees the board without following a bus.
 */
@Composable
fun NearbyBoard(now: Long, modifier: Modifier = Modifier) {
    val heard by com.ksjd.testem.p2p.P2pStore.board.collectAsState()
    val board = heard?.takeIf { now - it.atMs <= com.ksjd.testem.p2p.P2pPack.MAX_AGE_MS } ?: return
    BoardCard(board, now, modifier)
}

/** A heard stop board: its departures (tap one to follow it), delays and notices. */
@Composable
fun BoardCard(board: com.ksjd.testem.p2p.P2pPack.Board, now: Long, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sk = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "sk"
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.p2p_board_title, board.stop), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.p2p_board_from, ((now - board.atMs) / 60_000L).toInt().coerceAtLeast(0)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (board.departures.any { it.trip() != null }) Text(stringResource(R.string.p2p_board_tap),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            board.notices.forEach { (inSk, inEn) -> Notice(if (sk) inSk else inEn, tone = NoticeTone.Warning) }
            board.departures.filter { it.leavesMs + (it.delaySeconds ?: 0).coerceAtLeast(0) * 1000L > now - 60_000L }.take(8).forEach { d ->
                // Tap: follow it (stops from the offline timetables, the delay from phones nearby, emHUB once there is data).
                val trip = d.trip()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (trip == null) Modifier else Modifier.clickable {
                        com.ksjd.testem.live.TripTracking.start(context, trip)
                        android.widget.Toast.makeText(context, context.getString(R.string.p2p_board_following, d.line), android.widget.Toast.LENGTH_SHORT).show()
                    }.padding(vertical = 4.dp)
                ) {
                    val kind = lineKind(d.line)
                    KindPlate(d.line, kind)
                    Spacer(Modifier.width(10.dp))
                    Text(d.headsign, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    if (d.delaySeconds != null) { DelayLabel(d.delaySeconds); Spacer(Modifier.width(6.dp)) }
                    Text(com.ksjd.testem.ui.Format.clock(d.leavesMs), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** 2.19.0: whether the Departures search asks phones around (no data, helping phones nearby on); checked every 5 s. */
@Composable
fun rememberAskNearby(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = androidx.compose.runtime.remember { com.ksjd.testem.CredentialsManager(context) }
    val ask by androidx.compose.runtime.produceState(false) {
        while (true) { value = prefs.getP2pNearby() && !com.ksjd.testem.p2p.P2p.online(context); kotlinx.coroutines.delay(5_000L) }
    }
    return ask
}

/** The Departures search without data: stops from the offline timetables; a tap asks phones around for its departures. */
@Composable
fun AskResults(query: String, modifier: Modifier = Modifier, onAsked: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val found = androidx.compose.runtime.remember(query) { com.ksjd.testem.offline.OfflineTimetables.suggest(query, 8) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.p2p_ask_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (found.isEmpty()) Text(stringResource(R.string.p2p_ask_no_offline), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        found.forEach { s ->
            Text(s.selectedText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
                .clickable { com.ksjd.testem.p2p.P2pAsk.ask(context, s.value, s.selectedText); onAsked() }.padding(vertical = 10.dp))
        }
    }
}

/**
 * 2.19.0: without data, what was asked of phones around (from the Departures search): waiting, then the answer. Asking
 * stops when the tab closes, or after 5 minutes without an answer.
 */
@Composable
fun AskNearbyPhones(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000L) }
    }
    val asking by com.ksjd.testem.p2p.P2pAsk.asking.collectAsState()
    val boards by com.ksjd.testem.p2p.P2pStore.boards.collectAsState()
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.ksjd.testem.p2p.P2pAsk.stop(context) } }
    val a = asking ?: return
    // Asked long enough: stop (the battery, and nobody near has data).
    if (now - a.sinceMs > 5 * 60_000L && boards[a.stationId] == null) com.ksjd.testem.p2p.P2pAsk.stop(context)
    val answer = boards[a.stationId]?.takeIf { now - it.atMs <= com.ksjd.testem.p2p.P2pPack.MAX_AGE_MS }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (answer == null) {
            Notice(stringResource(R.string.p2p_ask_waiting, a.name, ((now - a.sinceMs) / 1000L).toInt()),
                actionLabel = stringResource(R.string.p2p_ask_stop), onAction = { com.ksjd.testem.p2p.P2pAsk.stop(context) })
        } else {
            BoardCard(answer, now)
            androidx.compose.material3.TextButton(onClick = { com.ksjd.testem.p2p.P2pAsk.stop(context) }) {
                Text(stringResource(R.string.p2p_ask_done))
            }
        }
    }
}
