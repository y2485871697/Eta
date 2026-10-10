package io.github.mangi.eta.agent.pet

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlinx.coroutines.delay
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt

internal class WhaleMaidOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var petView: ComposeView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var cabinetView: ComposeView? = null
    private var cabinetParams: WindowManager.LayoutParams? = null
    private var laidOutWidth = 0
    private var laidOutHeight = 0
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private var backCallback: OnBackInvokedCallback? = null
    private var snapshot by mutableStateOf(WhaleMaidSnapshot(
        enabled = true,
        workSpeechEnabled = true,
        globalVisible = false,
        satiety = 5_000,
        scale = 1f,
        x = -1,
        y = -1,
        mood = "idle",
        speech = "",
        speechVisible = false,
        thinking = false,
        memories = emptyList(),
        recentTasks = emptyList(),
    ))
    private var cabinetOpen by mutableStateOf(false)
    private var dragging = false
    private val listener: (WhaleMaidSnapshot) -> Unit = { next ->
        mainHandler.post {
            snapshot = next
            if (!next.enabled) {
                stopSelf()
            } else {
                syncPetWindow()
            }
        }
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        running = true
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        WhaleMaidStore.addListener(listener)
        snapshot = WhaleMaidStore.snapshot(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!WhaleMaidStore.isEnabled(this) || !Settings.canDrawOverlays(this) || intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        showPet()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        WhaleMaidStore.removeListener(listener)
        removeCabinet()
        removePet()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (runningService === this) running = false
        super.onDestroy()
    }

    private fun showPet() {
        if (petView != null) {
            syncPetWindow()
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        val view = composeView {
            val appearance by AppearanceSettingsRepository.settingsFlow()
                .collectAsState(initial = AppearanceSettings())
            AgentAppTheme(appearance = appearance, applyInterfaceScale = false) {
                WhaleMaidPet(
                    snapshot = snapshot,
                    onDrag = { dx, dy -> moveBy(dx, dy) },
                    onDragEnd = {
                        dragging = false
                        WhaleMaidController.setPosition(this, petParams?.x ?: 0, petParams?.y ?: 0)
                    },
                    moveWithoutRedraw = true,
                    onTap = { openCabinet() },
                    onDismissSpeech = { WhaleMaidController.dismissSpeech(this) },
                    onPoseFinished = { mood -> WhaleMaidController.finishPose(this, mood) },
                    onMeasured = { width, height -> holdPetWhileContentChanges(width, height) },
                )
            }
        }
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (!addView(wm, view, params)) return
        windowManager = wm
        petView = view
        petParams = params
        syncPetWindow()
    }

    private fun petPixels(): Int =
        (128f * snapshot.scale * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    private fun horizontalRange(): IntRange {
        val screenW = resources.displayMetrics.widthPixels
        val pet = petPixels()
        val window = laidOutWidth.takeIf { it > 0 } ?: pet
        val inset = ((window - pet) / 2).coerceAtLeast(0)
        return -inset..(screenW - pet - inset).coerceAtLeast(-inset)
    }

    private fun verticalRange(): IntRange {
        val screenH = resources.displayMetrics.heightPixels
        val pet = petPixels()
        val extra = (laidOutHeight - pet).coerceAtLeast(0)
        return -extra..(screenH - pet - extra).coerceAtLeast(-extra)
    }

    private fun holdPetWhileContentChanges(width: Int, height: Int) {
        val wm = windowManager ?: return
        val view = petView ?: return
        val params = petParams ?: return
        if (width <= 0 || height <= 0 || dragging) return
        val previousWidth = laidOutWidth
        val previousHeight = laidOutHeight
        if (previousWidth == width && previousHeight == height) return
        laidOutWidth = width
        laidOutHeight = height
        // The first measurement only records the size. Later growth is the bubble
        // appearing above and around the pet, so the window origin moves to keep
        // the pet itself on the same pixels.
        if (previousWidth > 0 && previousHeight > 0) {
            params.x -= (width - previousWidth) / 2
            params.y -= height - previousHeight
        }
        val xRange = horizontalRange()
        val yRange = verticalRange()
        params.x = params.x.coerceIn(xRange.first, xRange.last)
        params.y = params.y.coerceIn(yRange.first, yRange.last)
        runCatching { wm.updateViewLayout(view, params) }
        WhaleMaidController.setPosition(this, params.x, params.y)
    }

    private fun syncPetWindow() {
        val wm = windowManager ?: return
        val view = petView ?: return
        val params = petParams ?: return
        if (dragging) return
        val density = resources.displayMetrics.density
        val screenH = resources.displayMetrics.heightPixels
        val xRange = horizontalRange()
        val yRange = verticalRange()
        if (params.width <= 0 || params.height <= 0) {
            if (snapshot.x < 0 || snapshot.y < 0) {
                params.x = (xRange.last - (16 * density).roundToInt()).coerceIn(xRange.first, xRange.last)
                params.y = (screenH * 0.62f).roundToInt().coerceIn(yRange.first, yRange.last)
                WhaleMaidController.setPosition(this, params.x, params.y)
            } else {
                params.x = snapshot.x.coerceIn(xRange.first, xRange.last)
                params.y = snapshot.y.coerceIn(yRange.first, yRange.last)
            }
            runCatching { wm.updateViewLayout(view, params) }
            return
        }
        if (snapshot.x < 0 || snapshot.y < 0) {
            params.x = (xRange.last - (16 * density).roundToInt()).coerceIn(xRange.first, xRange.last)
            params.y = (screenH * 0.62f).roundToInt().coerceIn(yRange.first, yRange.last)
            WhaleMaidController.setPosition(this, params.x, params.y)
        } else {
            params.x = snapshot.x.coerceIn(xRange.first, xRange.last)
            params.y = snapshot.y.coerceIn(yRange.first, yRange.last)
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun moveBy(dx: Int, dy: Int) {
        val wm = windowManager ?: return
        val view = petView ?: return
        val params = petParams ?: return
        dragging = true
        val xRange = horizontalRange()
        val yRange = verticalRange()
        params.x = (params.x + dx).coerceIn(xRange.first, xRange.last)
        params.y = (params.y + dy).coerceIn(yRange.first, yRange.last)
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun openCabinet() {
        if (cabinetView != null || snapshot.thinking) return
        val wm = windowManager ?: return
        cabinetOpen = true
        val view = composeView {
            val appearance by AppearanceSettingsRepository.settingsFlow()
                .collectAsState(initial = AppearanceSettings())
            AgentAppTheme(appearance = appearance, applyInterfaceScale = false) {
                WhaleMaidCabinet(
                    snapshot = snapshot,
                    onDismiss = { removeCabinet() },
                    onFeed = { tokens, name ->
                        removeCabinet()
                        WhaleMaidController.feed(this, tokens, name)
                    },
                    onScale = { WhaleMaidController.setScale(this, it) },
                    onClear = { WhaleMaidController.clearMemories(this) },
                )
            }
        }
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            dimAmount = 0.35f
        }
        if (!addView(wm, view, params)) {
            cabinetOpen = false
            return
        }
        cabinetView = view
        cabinetParams = params
        registerBack(view)
    }

    private fun removeCabinet() {
        cabinetOpen = false
        unregisterBack()
        val wm = windowManager
        val view = cabinetView
        cabinetView = null
        cabinetParams = null
        if (wm != null && view != null) runCatching { wm.removeView(view) }
    }

    private fun removePet() {
        val wm = windowManager
        val view = petView
        petView = null
        petParams = null
        windowManager = null
        if (wm != null && view != null) runCatching { wm.removeView(view) }
    }

    private fun composeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@WhaleMaidOverlayService)
            setViewTreeSavedStateRegistryOwner(this@WhaleMaidOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent(content)
        }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "EtaWhaleMaid"
    }

    private fun addView(wm: WindowManager, view: View, params: WindowManager.LayoutParams): Boolean {
        return runCatching { wm.addView(view, params) }.onFailure { throwable ->
            AndroidAgentLogger.warn("Whale maid overlay add failed: type=${throwable.javaClass.simpleName}")
        }.isSuccess
    }

    private fun registerBack(view: View) {
        unregisterBack()
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return
        val callback = OnBackInvokedCallback { removeCabinet() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        backDispatcher = dispatcher
        backCallback = callback
    }

    private fun unregisterBack() {
        val dispatcher = backDispatcher
        val callback = backCallback
        backDispatcher = null
        backCallback = null
        if (dispatcher != null && callback != null) dispatcher.unregisterOnBackInvokedCallback(callback)
    }

    companion object {
        const val ACTION_SHOW = "io.github.mangi.eta.pet.SHOW"
        const val ACTION_STOP = "io.github.mangi.eta.pet.STOP"
        @Volatile private var running = false
        private var runningService: WhaleMaidOverlayService? = null
        fun isRunning(): Boolean = running

        init {
            // runningService kept for destroy identity; assigned below via instance helper
        }
    }

    init {
        runningService = this
    }
}

@Composable
internal fun WhaleMaidInAppHost() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var snapshot by remember { mutableStateOf(WhaleMaidStore.snapshot(context)) }
    DisposableEffect(Unit) {
        val listener: (WhaleMaidSnapshot) -> Unit = { snapshot = it }
        WhaleMaidStore.addListener(listener)
        onDispose { WhaleMaidStore.removeListener(listener) }
    }
    if (!snapshot.enabled || snapshot.globalVisible) return
    var cabinetOpen by remember { mutableStateOf(false) }
    var originX by remember(snapshot.x) { mutableIntStateOf(snapshot.x) }
    var originY by remember(snapshot.y) { mutableIntStateOf(snapshot.y) }
    var laidOutWidth by remember { mutableIntStateOf(0) }
    var laidOutHeight by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val pet = with(density) { (128f * snapshot.scale).dp.roundToPx() }.coerceAtLeast(1)
    val screenW = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenH = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val insetX = ((laidOutWidth - pet) / 2).coerceAtLeast(0)
    val extraY = (laidOutHeight - pet).coerceAtLeast(0)
    val minX = -insetX
    val maxX = (screenW - pet - insetX).coerceAtLeast(minX)
    val minY = -extraY
    val maxY = (screenH - pet - extraY).coerceAtLeast(minY)
    if (originX < 0 || originY < 0) {
        originX = (maxX - with(density) { 16.dp.roundToPx() }).coerceIn(minX, maxX)
        originY = (screenH * 0.62f).roundToInt().coerceIn(minY, maxY)
    }
    Box(Modifier.fillMaxSize()) {
        WhaleMaidPet(
            snapshot = snapshot,
            onDrag = { dx, dy ->
                originX = (originX + dx).coerceIn(minX, maxX)
                originY = (originY + dy).coerceIn(minY, maxY)
            },
            moveWithoutRedraw = true,
            onDragEnd = { WhaleMaidController.setPosition(context, originX, originY) },
            onTap = { if (!snapshot.thinking) cabinetOpen = true },
            onDismissSpeech = { WhaleMaidController.dismissSpeech(context) },
            onPoseFinished = { mood -> WhaleMaidController.finishPose(context, mood) },
            onMeasured = { width, height ->
                if (width <= 0 || height <= 0) return@WhaleMaidPet
                val previousWidth = laidOutWidth
                val previousHeight = laidOutHeight
                laidOutWidth = width
                laidOutHeight = height
                if (previousWidth > 0 && previousHeight > 0) {
                    originX -= (width - previousWidth) / 2
                    originY -= height - previousHeight
                    originX = originX.coerceIn(minX, maxX)
                    originY = originY.coerceIn(minY, maxY)
                    WhaleMaidController.setPosition(context, originX, originY)
                }
            },
            modifier = Modifier.offset { IntOffset(originX, originY) },
        )
        if (cabinetOpen) {
            WhaleMaidCabinet(
                snapshot = snapshot,
                onDismiss = { cabinetOpen = false },
                onFeed = { tokens, name ->
                    cabinetOpen = false
                    WhaleMaidController.feed(context, tokens, name)
                },
                onScale = { WhaleMaidController.setScale(context, it) },
                onClear = { WhaleMaidController.clearMemories(context) },
            )
        }
    }
}

private val whaleMaidMoodRow = mapOf(
    "idle" to 0,
    "hungry" to 1,
    "happy" to 2,
    "angry" to 3,
    "eating" to 4,
    "scared" to 5,
    "sad" to 6,
    "thinking" to 7,
)

@Composable
private fun WhaleMaidPet(
    snapshot: WhaleMaidSnapshot,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onTap: () -> Unit,
    onDismissSpeech: () -> Unit,
    onPoseFinished: (String) -> Unit,
    onMeasured: (Int, Int) -> Unit,
    moveWithoutRedraw: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    val pet = 128.dp * snapshot.scale
    val bubbleAlpha = androidx.compose.runtime.remember { Animatable(1f) }
    LaunchedEffect(snapshot.speechVisible, snapshot.speech) {
        if (!snapshot.speechVisible || snapshot.speech.isBlank()) {
            bubbleAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        bubbleAlpha.snapTo(1f)
        delay(2600)
        bubbleAlpha.animateTo(0f, tween(durationMillis = 500))
        onDismissSpeech()
    }
    LaunchedEffect(snapshot.mood, snapshot.thinking) {
        val mood = snapshot.mood
        if (snapshot.thinking || mood == "idle") return@LaunchedEffect
        delay(1500)
        onPoseFinished(mood)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .onSizeChanged { onMeasured(it.width, it.height) }
            .graphicsLayer {
                translationX = dragX
                translationY = dragY
            },
    ) {
        if (snapshot.speechVisible && snapshot.speech.isNotBlank()) {
            Text(
                text = snapshot.speech,
                color = Color(0xFF1E293B),
                fontSize = (13f * snapshot.scale).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .graphicsLayer { alpha = bubbleAlpha.value }
                    .padding(bottom = 6.dp)
                    // Short speech wraps its text; long speech wraps at the scaled cap.
                    .widthIn(max = 220.dp * snapshot.scale)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .border(2.dp, Color(0xFF3B82F6), RoundedCornerShape(12.dp))
                    .noRippleClickable(onClick = onDismissSpeech)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        WhaleMaidSprite(
            mood = snapshot.mood,
            modifier = Modifier
                .size(pet)
                .pointerInput(snapshot.mood) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var moved = 0f
                        var pendingX = 0f
                        var pendingY = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            moved += delta.getDistance()
                            if (moved >= 12f) {
                                if (moveWithoutRedraw) {
                                    pendingX += delta.x
                                    pendingY += delta.y
                                    dragX = pendingX
                                    dragY = pendingY
                                } else {
                                    onDrag(delta.x.roundToInt(), delta.y.roundToInt())
                                }
                            }
                            change.consume()
                        }
                        if (moved < 12f) {
                            onTap()
                        } else if (moveWithoutRedraw) {
                            onDrag(pendingX.roundToInt(), pendingY.roundToInt())
                            dragX = 0f
                            dragY = 0f
                            onDragEnd()
                        } else {
                            onDragEnd()
                        }
                    }
                },
        )
    }
}

@Composable
private fun WhaleMaidSprite(mood: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val image = remember {
        context.assets.open("whale-maid/pet_sprite.png").use { stream ->
            BitmapFactory.decodeStream(stream)?.asImageBitmap()
        }
    }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(188)
            frame = (frame + 1) % 4
        }
    }
    val row = whaleMaidMoodRow[mood] ?: 0
    Canvas(modifier) {
        val bitmap = image ?: return@Canvas
        val frameSize = bitmap.width / 4
        val side = minOf(size.width, size.height)
        drawImage(
            image = bitmap,
            srcOffset = IntOffset(frame * frameSize, row * frameSize),
            srcSize = IntSize(frameSize, frameSize),
            dstOffset = IntOffset(((size.width - side) / 2f).roundToInt(), ((size.height - side) / 2f).roundToInt()),
            dstSize = IntSize(side.roundToInt(), side.roundToInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

private data class WhaleMaidFood(val name: Int, val tokens: Int, val asset: String)

private val whaleMaidFoods = listOf(
    WhaleMaidFood(R.string.whale_maid_food_grain, 1, "rice_grain"),
    WhaleMaidFood(R.string.whale_maid_food_spoon, 1_000, "rice_spoon"),
    WhaleMaidFood(R.string.whale_maid_food_bowl, 3_000, "rice_bowl"),
    WhaleMaidFood(R.string.whale_maid_food_pot, 10_000, "rice_pot"),
)

@Composable
private fun WhaleMaidCabinet(
    snapshot: WhaleMaidSnapshot,
    onDismiss: () -> Unit,
    onFeed: (Int, String) -> Unit,
    onScale: (Float) -> Unit,
    onClear: () -> Unit,
) {
    val band = whaleMaidSatietyBand(snapshot.satiety)
    val percent = whaleMaidSatietyPercent(snapshot.satiety)
    val bandText = stringResource(when (band) {
        WhaleMaidSatietyBand.STARVING -> R.string.whale_maid_satiety_starving
        WhaleMaidSatietyBand.HUNGRY -> R.string.whale_maid_satiety_hungry
        WhaleMaidSatietyBand.PECKISH -> R.string.whale_maid_satiety_peckish
        WhaleMaidSatietyBand.FULL -> R.string.whale_maid_satiety_full
        WhaleMaidSatietyBand.STUFFED -> R.string.whale_maid_satiety_stuffed
    })
    val bar = when (band) {
        WhaleMaidSatietyBand.STARVING -> Color(0xFFDC2626)
        WhaleMaidSatietyBand.HUNGRY -> Color(0xFFEA580C)
        WhaleMaidSatietyBand.STUFFED -> Color(0xFF3B82F6)
        else -> Color(0xFF16A34A)
    }
    Box(Modifier.fillMaxSize().noRippleClickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(24.dp)
                .width(320.dp)
                .heightIn(max = 520.dp)
                .background(Color(0xFFFFF7ED), RoundedCornerShape(18.dp))
                .noRippleClickable {}
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.whale_maid_cabinet), fontWeight = FontWeight.Bold, color = Color(0xFF4A2812))
            Text("${snapshot.satiety} / $WHALE_MAID_MAX_SATIETY ($percent%)", color = bar, fontWeight = FontWeight.Medium)
            Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xFFFED7AA), RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth(percent / 100f).height(8.dp).background(bar, RoundedCornerShape(4.dp)))
            }
            Text(
                if (snapshot.thinking) stringResource(R.string.whale_maid_thinking) else bandText,
                color = Color(0xFF4A2812),
            )
            whaleMaidFoods.forEach { food ->
                val name = stringResource(food.name)
                Row(
                    Modifier.fillMaxWidth().noRippleClickable(enabled = !snapshot.thinking) { onFeed(food.tokens, name) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WhaleMaidAssetImage(food.asset, Modifier.size(48.dp))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(name, color = Color(0xFF4A2812))
                        Text("+${food.tokens}", color = Color(0xFF92400E), fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.whale_maid_scale), color = Color(0xFF4A2812))
                Text(
                    "${(snapshot.scale * 100).roundToInt()}%  ${stringResource(R.string.whale_maid_reset)}",
                    color = Color(0xFF1D4ED8),
                    modifier = Modifier.noRippleClickable { onScale(1f) },
                )
            }
            Slider(
                value = snapshot.scale * 100f,
                onValueChange = { onScale(it / 100f) },
                valueRange = 50f..180f,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.whale_maid_memories), color = Color(0xFF4A2812))
                Text(stringResource(R.string.whale_maid_clear), color = Color(0xFF1D4ED8), modifier = Modifier.noRippleClickable(onClick = onClear))
            }
            if (snapshot.memories.isEmpty()) {
                Text(stringResource(R.string.whale_maid_empty), color = Color(0xFF78716C), fontSize = 12.sp)
            } else {
                snapshot.memories.forEach { memory ->
                    Text("[${memory.mood}] ${memory.speech}", color = Color(0xFF44403C), fontSize = 13.sp)
                }
            }
        }
    }
}


private fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    enabled = enabled,
    onClick = onClick,
)

@Composable
private fun WhaleMaidAssetImage(name: String, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val image = remember(name) {
        context.assets.open("whale-maid/$name.png").use { stream ->
            BitmapFactory.decodeStream(stream)?.asImageBitmap()
        }
    }
    if (image != null) {
        Image(bitmap = image, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier)
    }
}
