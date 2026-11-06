package nl.markmaaktmedia.tandem.watch.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import nl.markmaaktmedia.tandem.watch.R
import nl.markmaaktmedia.tandem.watch.link.WatchLink
import nl.markmaaktmedia.tandem.watch.protocol.CallActionKind
import nl.markmaaktmedia.tandem.watch.protocol.CallMsg
import nl.markmaaktmedia.tandem.watch.protocol.CallStatus
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemMotion
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemOnline
import nl.markmaaktmedia.tandem.watch.ui.theme.TandemWatchTheme

/** Opens over the watch face when the phone rings. Controls the call only: the sound stays on the phone. */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            TandemWatchTheme {
                AppScaffold(timeText = { TimeText() }) {
                    val ui by WatchLink.ui.collectAsState()
                    val call = ui.call
                    LaunchedEffect(call == null) { if (call == null) finish() }
                    if (call != null) CallScreen(call)
                }
            }
        }
    }
}

/** Nothing scrolls here: during a ring there is no time to go looking for a button. */
@Composable
internal fun CallScreen(call: CallMsg) {
    val context = LocalContext.current
    val ringing = call.state == CallStatus.RINGING
    val name = call.name ?: call.number ?: stringResource(R.string.call_unknown)
    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = if (ringing) 44.dp else 62.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    name,
                    style = if (name.length > LONG_NAME) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                // Ringing needs no words: the two buttons say it.
                if (!ringing) {
                    Text(
                        stringResource(R.string.call_active),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (ringing) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        FilledIconButton(
                            onClick = { perform(context, CallActionKind.REJECT) },
                            modifier = Modifier.size(CALL_BUTTON),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            shapes = IconButtonDefaults.animatedShapes(),
                        ) { Icon(painterResource(R.drawable.sym_call_end), stringResource(R.string.reject), modifier = Modifier.size(28.dp)) }
                        AnswerButton { perform(context, CallActionKind.ANSWER) }
                    }
                }
            }
            if (ringing) {
                EdgeButton(
                    onClick = { perform(context, CallActionKind.SILENCE) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    buttonSize = EdgeButtonSize.ExtraSmall,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) { Text(stringResource(R.string.silence)) }
            } else {
                EdgeButton(
                    onClick = { perform(context, CallActionKind.HANGUP) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    buttonSize = EdgeButtonSize.Medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Icon(painterResource(R.drawable.sym_call_end), stringResource(R.string.hang_up), modifier = Modifier.size(28.dp)) }
            }
        }
    }
}

/** From about here a name no longer fits in big letters, not even on two lines. */
private const val LONG_NAME = 18

private val CALL_BUTTON = 56.dp

/** The cookie turns while the phone rings and rounds off under the finger, so the one thing to press can be seen from the corner of an eye. */
@Composable
private fun AnswerButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val depth by animateFloatAsState(if (pressed) 0f else COOKIE_DEPTH, TandemMotion.shapeSpec(), label = "answerShape")
    val turn by rememberInfiniteTransition(label = "answerTurn").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 16_000, easing = LinearEasing)),
        label = "answerTurnAngle",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(CALL_BUTTON)) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn }.background(TandemOnline, CookieShape(depth = depth)))
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize(), interactionSource = source) {
            Icon(painterResource(R.drawable.sym_call), stringResource(R.string.answer), tint = Color.Black, modifier = Modifier.size(28.dp))
        }
    }
}

/** A press that cannot reach the phone says so, instead of looking as if it worked. */
private fun perform(context: android.content.Context, kind: CallActionKind) {
    if (!WatchLink.sendCallAction(kind)) {
        Toast.makeText(context, R.string.phone_not_connected, Toast.LENGTH_SHORT).show()
    }
}
