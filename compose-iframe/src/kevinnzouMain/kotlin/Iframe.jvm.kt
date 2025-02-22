package net.lsafer.compose.iframe

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.multiplatform.webview.jsbridge.IJsMessageHandler
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.jsbridge.WebViewJsBridge
import com.multiplatform.webview.web.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class IframeState(
    val webview: WebViewState,
    val navigator: WebViewNavigator,
    val jsBridge: WebViewJsBridge,
    internal val _incoming: Channel<IframeIncomingEvent>,
    internal val _outgoing: Channel<IframeOutgoingEvent>,
) {
    actual val incoming: ReceiveChannel<IframeIncomingEvent> = _incoming
    actual val outgoing: SendChannel<IframeOutgoingEvent> = _outgoing
}

@Composable
actual fun rememberIframeState(url: String): IframeState {
    return rememberIframeState(url, mapOf())
}

@Composable
fun rememberIframeState(
    url: String,
    additionalHttpHeaders: Map<String, String> = emptyMap(),
): IframeState {
    val webview = rememberWebViewState(url, additionalHttpHeaders)
    val navigator = rememberWebViewNavigator()
    val jsBridge = remember { WebViewJsBridge() }
    val incoming = remember { Channel<IframeIncomingEvent>() }
    val outgoing = remember { Channel<IframeOutgoingEvent>() }

    return remember(
        webview,
        navigator,
        jsBridge,
        incoming,
        outgoing,
    ) {
        IframeState(
            webview = webview,
            navigator = navigator,
            jsBridge = jsBridge,
            _incoming = incoming,
            _outgoing = outgoing,
        )
    }
}

@Composable
actual fun Iframe(state: IframeState, modifier: Modifier) {
    Iframe(
        state = state,
        modifier = modifier,
        captureBackPresses = true,
        onCreated = {},
        onDispose = {},
        platformWebViewParams = null,
    )
}

@Composable
fun Iframe(
    state: IframeState,
    modifier: Modifier = Modifier,
    captureBackPresses: Boolean = true,
    onCreated: () -> Unit = {},
    onDispose: () -> Unit = {},
    platformWebViewParams: PlatformWebViewParams? = null,
) {
    val scope = rememberCoroutineScope()

    // establish jsBridge
    DisposableEffect(
        state.jsBridge,
        state.webview.loadingState,
    ) {
        if (state.webview.loadingState != LoadingState.Finished)
            return@DisposableEffect onDispose {}

        val jsBridgeHandler = object : IJsMessageHandler {
            override fun methodName() = "onMessageReceived"

            override fun handle(
                message: JsMessage,
                navigator: WebViewNavigator?,
                callback: (String) -> Unit
            ) {
                val iframeEvent: IframeIncomingEvent =
                    Json.decodeFromString(message.params)
                scope.launch(Dispatchers.IO) {
                    state._incoming.send(iframeEvent)
                }
                callback("")
            }
        }

        state.jsBridge.register(jsBridgeHandler)
        onDispose { state.jsBridge.unregister(jsBridgeHandler) }
    }

    // inject interop script
    LaunchedEffect(
        state.navigator,
        state.webview.content,
        state.webview.loadingState,
    ) {
        if (state.webview.loadingState != LoadingState.Finished)
            return@LaunchedEffect

        // language=javascript
        val script = """
            if (!window.kmpInteropEstablished) {
                window.parent.addEventListener("message", event => {
                    const iframeEvent = { data: event.data, origin: event.origin }
                    window.kmpJsBridge.callNative("onMessageReceived", JSON.stringify(iframeEvent))
                })
                window.kmpInteropEstablished = true
            }
        """.trimIndent()

        state.navigator.evaluateJavaScript(script)
    }

    // dispose outgoing messages
    LaunchedEffect(state.webview.loadingState) {
        if (state.webview.loadingState != LoadingState.Finished)
            return@LaunchedEffect

        launch {
            for (iframeEvent in state._outgoing) {
                val dataString = Json.encodeToString(iframeEvent.data)
                val targetOrigin = iframeEvent.targetOrigin

                // language=javascript
                val script = "window.postMessage($dataString, $targetOrigin)"

                suspendCancellableCoroutine { cont ->
                    state.navigator.evaluateJavaScript(script) {
                        cont.resume(Unit)
                    }
                }
            }
        }
    }

    WebViewCompat(
        state = state.webview,
        modifier = modifier,
        captureBackPresses = captureBackPresses,
        navigator = state.navigator,
        webViewJsBridge = state.jsBridge,
        onCreated = onCreated,
        onDispose = onDispose,
        platformWebViewParams = platformWebViewParams,
    )
}
