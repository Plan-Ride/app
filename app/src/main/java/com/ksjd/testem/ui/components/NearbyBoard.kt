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
    val context = androidx.compose.ui.platform.LocalContext.current
    val heard by com.ksjd.testem.p2p.P2pStore.board.collectAsState()
    val board = heard?.takeIf { now - it.atMs <= com.ksjd.testem.p2p.P2pPack.MAX_AGE_MS } ?: return
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
