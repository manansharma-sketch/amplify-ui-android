/*
 * Fitpass addition to the amplify-ui-android liveness module (Apache-2.0).
 *
 * Adds a Fitpass-branded layout around the standard Face Liveness challenge.
 * The original FaceLivenessDetector.kt is NOT modified, so merging AWS updates stays easy.
 *
 * Unchanged on purpose: oval size and position (sent by the server), timeouts,
 * countdown, face-fit logic, and the light challenge.
 */
package com.amplifyframework.ui.liveness.ui

import android.graphics.RectF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.amplifyframework.auth.AWSCredentials
import com.amplifyframework.auth.AWSCredentialsProvider
import com.amplifyframework.core.Action
import com.amplifyframework.core.Consumer
import com.amplifyframework.predictions.models.FaceLivenessChallengeType
import com.amplifyframework.ui.liveness.camera.LivenessCoordinator
import com.amplifyframework.ui.liveness.camera.OnChallengeComplete
import com.amplifyframework.ui.liveness.ml.FaceDetector
import com.amplifyframework.ui.liveness.model.FaceLivenessDetectionException
import com.amplifyframework.ui.liveness.model.LivenessCheckState
import com.amplifyframework.ui.liveness.ui.helper.VideoViewportSize
import com.amplifyframework.ui.liveness.util.hasCameraPermission
import kotlinx.coroutines.launch

/** Visual settings for the Fitpass scan screen. */
data class FitpassScanStyle(
    val accent: Color = Color(0xFFD6453D),
    val pageBackground: Color = Color.White,
    val subtitle: String = "Hold your head within the frame to complete the scan",
    val subtitleColor: Color = Color(0xFF6B6F76),
    val showRecordingIndicator: Boolean = false,
    val showCancelButton: Boolean = true
)

/**
 * Same behaviour as [FaceLivenessDetector], with a Fitpass layout:
 * header, camera with the AWS oval plus corner brackets, status text, footer.
 *
 * The AWS start view is always skipped. Show your own intro screen before this.
 */
@Composable
fun FitpassFaceLivenessDetector(
    sessionId: String,
    region: String,
    onComplete: Action,
    onError: Consumer<FaceLivenessDetectionException>,
    credentialsProvider: AWSCredentialsProvider<AWSCredentials>? = null,
    challengeOptions: ChallengeOptions = ChallengeOptions(),
    videoOptions: VideoOptions = VideoOptions(),
    style: FitpassScanStyle = FitpassScanStyle(),
    header: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val key = DetectorStateKey(sessionId, region, credentialsProvider, videoOptions)
    var isFinished by remember(key) { mutableStateOf(false) }
    val currentOnComplete by rememberUpdatedState(onComplete)
    val currentOnError by rememberUpdatedState(onError)

    if (isFinished) return

    if (!LocalContext.current.hasCameraPermission()) {
        LaunchedEffect(key) {
            isFinished = true
            currentOnError.accept(FaceLivenessDetectionException.CameraPermissionDeniedException())
        }
        return
    }

    if (sessionId.isBlank()) {
        LaunchedEffect(key) {
            isFinished = true
            currentOnError.accept(
                FaceLivenessDetectionException.SessionNotFoundException("Session ID cannot be empty.")
            )
        }
        return
    }

    LockPortraitOrientation { resetOrientation ->
        Surface(color = style.pageBackground) {
            AlwaysOnMaxBrightnessScreen()
            FitpassChallengeView(
                key = key,
                sessionId = sessionId,
                region = region,
                credentialsProvider = credentialsProvider,
                challengeOptions = challengeOptions,
                videoOptions = videoOptions,
                style = style,
                header = header,
                footer = footer,
                onChallengeComplete = {
                    scope.launch {
                        if (!isFinished) {
                            isFinished = true
                            resetOrientation()
                            currentOnComplete.call()
                        }
                    }
                },
                onChallengeFailed = {
                    scope.launch {
                        if (!isFinished) {
                            isFinished = true
                            resetOrientation()
                            currentOnError.accept(it)
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun FitpassChallengeView(
    key: Any,
    sessionId: String,
    region: String,
    credentialsProvider: AWSCredentialsProvider<AWSCredentials>?,
    challengeOptions: ChallengeOptions,
    videoOptions: VideoOptions,
    style: FitpassScanStyle,
    header: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    onChallengeComplete: OnChallengeComplete,
    onChallengeFailed: Consumer<FaceLivenessDetectionException>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var coordinator by remember { mutableStateOf<LivenessCoordinator?>(null) }
    val currentOnChallengeComplete by rememberUpdatedState(onChallengeComplete)
    val currentOnChallengeFailed by rememberUpdatedState(onChallengeFailed)

    DisposableEffect(key) {
        try {
            coordinator = LivenessCoordinator(
                context,
                lifecycleOwner,
                sessionId,
                region,
                credentialsProvider,
                true, // disableStartView: Fitpass shows its own intro screen
                challengeOptions,
                videoOptions = videoOptions,
                onChallengeComplete = { currentOnChallengeComplete() },
                onChallengeFailed = { currentOnChallengeFailed.accept(it) }
            )
        } catch (e: Exception) {
            currentOnChallengeFailed.accept(
                FaceLivenessDetectionException(
                    message = "Failed to initialize video components required for Liveness check.",
                    throwable = e
                )
            )
        }
        onDispose { coordinator?.destroy(context) }
    }

    val livenessCoordinator = coordinator ?: return
    val livenessState = livenessCoordinator.livenessState
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.pageBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            header()

            // Camera area. The viewport is measured from this box, so the AWS oval
            // is scaled to fit here. Its position relative to the video is unchanged.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        livenessState.updateVideoViewportSize(VideoViewportSize.create(it.size, density))
                    }
            ) {
                val viewport = livenessState.videoViewportSize
                if (viewport != null) {
                    AndroidView(
                        { livenessCoordinator.previewTextureView },
                        Modifier
                            .size(viewport.viewportDpSize)
                            .align(Alignment.Center)
                    )

                    // Step 1 (initial face): AWS has not sent the oval yet, so we draw
                    // AWS's own placeholder oval. Step 2: the real oval from the server.
                    // The drawn oval animates between them; the check itself always uses
                    // the server's oval, so this animation is visual only.
                    val serverRect = livenessState.faceGuideRect

                    // Remember the last oval AWS sent, so we know where to shrink to.
                    val lastServerRect = remember { mutableStateOf<RectF?>(null) }
                    LaunchedEffect(serverRect) {
                        if (serverRect != null) lastServerRect.value = serverRect
                    }
                    val finalRect = lastServerRect.value

                    // Step 3 (done): the face matched, or AWS cleared the oval after we had one.
                    // Shrink the oval to a point at its own centre instead of jumping back.
                    val finished = finalRect != null &&
                        (livenessState.faceMatched || serverRect == null)

                    val targetRect = when {
                        finished -> RectF(
                            finalRect!!.centerX(),
                            finalRect.centerY(),
                            finalRect.centerX(),
                            finalRect.centerY()
                        )
                        serverRect != null -> serverRect
                        else -> PLACEHOLDER_OVAL
                    }
                    val animated by animateRectAsState(
                        targetValue = Rect(
                            targetRect.left,
                            targetRect.top,
                            targetRect.right,
                            targetRect.bottom
                        ),
                        animationSpec = tween(
                            durationMillis = OVAL_ANIMATION_MS,
                            easing = FastOutSlowInEasing
                        ),
                        label = "fitpassOval"
                    )
                    val shownRect = RectF(animated.left, animated.top, animated.right, animated.bottom)

                    FaceGuide(
                        modifier = Modifier
                            .fillMaxSize()
                            .align(Alignment.Center),
                        faceGuideRect = shownRect,
                        videoViewportSize = viewport,
                        backgroundColor = style.pageBackground
                    )
                    // Fitpass red corner brackets around the oval (drawing only)
                    Box(
                        modifier = Modifier
                            .size(viewport.viewportDpSize)
                            .align(Alignment.Center)
                    ) {
                        // While shrinking, the brackets shrink with the oval and reach zero together.
                        val bracketScale = if (finished) {
                            (shownRect.width() / finalRect!!.width()).coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                        OvalCornerBrackets(
                            ovalRect = viewport.getScaledBoundingRect(shownRect),
                            color = style.accent,
                            scale = bracketScale,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (style.showRecordingIndicator && serverRect != null) {
                            RecordingIndicator(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                            )
                        }
                    }
                    if (livenessState.loadingCameraPreview) {
                        CircularProgressIndicator(
                            color = style.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Status: AWS instruction text (e.g. "Move closer") + Fitpass subtitle.
            // Fixed height: if this area grew or shrank, the camera box above would
            // resize and the oval would visibly change size.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(STATUS_AREA_HEIGHT),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            val checkState = livenessState.livenessCheckState
            val challengeType = livenessState.livenessSessionInfo?.challengeType
            if (fitpassShouldShowInstruction(checkState, challengeType)) {
                InstructionMessage(checkState)
            }
            if (livenessState.faceGuideRect != null &&
                checkState.instructionId == FaceDetector.FaceOvalPosition.TOO_FAR.instructionStringRes
            ) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { livenessState.faceMatchPercentage },
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .width(180.dp)
                        .height(8.dp),
                    color = style.accent,
                    trackColor = style.accent.copy(alpha = 0.15f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = style.subtitle,
                color = style.subtitleColor,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            }
            Spacer(Modifier.height(16.dp))

            footer()
        }

        // Light challenge stays full-screen so enough light reaches the face
        if (livenessState.faceMatched) {
            if (livenessState.livenessSessionInfo?.challengeType ==
                FaceLivenessChallengeType.FaceMovementAndLightChallenge
            ) {
                FreshnessChallenge(
                    key,
                    modifier = Modifier.fillMaxSize(),
                    colors = livenessState.colorChallenge!!.challengeColors,
                    onColorDisplayed = { currentColor, previousColor, sequenceNumber, colorStart ->
                        livenessCoordinator.processColorDisplayed(
                            currentColor,
                            previousColor,
                            sequenceNumber,
                            colorStart
                        )
                    },
                    onComplete = { livenessCoordinator.processLivenessCheckComplete() }
                )
            } else {
                LaunchedEffect(key) { livenessCoordinator.processLivenessCheckComplete() }
            }
        }

        if (style.showCancelButton) {
            CancelChallengeButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                action = {
                    livenessCoordinator.processSessionError(
                        FaceLivenessDetectionException.UserCancelledException(),
                        true
                    )
                }
            )
        }
    }
}

private val STATUS_AREA_HEIGHT = 120.dp

/** AWS's own placeholder oval for the 480x640 preview (same values as AWS's start view). */
private val PLACEHOLDER_OVAL = RectF(120f, 126f, 360f, 514f)

private const val OVAL_ANIMATION_MS = 450

/** Four rounded corner brackets drawn around the oval's bounding box. */
@Composable
private fun OvalCornerBrackets(
    ovalRect: RectF,
    color: Color,
    modifier: Modifier = Modifier,
    scale: Float = 1f
) {
    val density = LocalDensity.current
    val gap = with(density) { 14.dp.toPx() } * scale
    val arm = with(density) { 44.dp.toPx() } * scale
    val radius = with(density) { 18.dp.toPx() } * scale
    val strokeWidth = with(density) { 5.dp.toPx() } * scale

    Canvas(modifier) {
        if (scale <= 0.01f) return@Canvas
        val l = ovalRect.left - gap
        val t = ovalRect.top - gap
        val r = ovalRect.right + gap
        val b = ovalRect.bottom + gap
        val path = Path().apply {
            // top-left
            moveTo(l, t + arm); lineTo(l, t + radius)
            cubicTo(l, t, l, t, l + radius, t); lineTo(l + arm, t)
            // top-right
            moveTo(r - arm, t); lineTo(r - radius, t)
            cubicTo(r, t, r, t, r, t + radius); lineTo(r, t + arm)
            // bottom-right
            moveTo(r, b - arm); lineTo(r, b - radius)
            cubicTo(r, b, r, b, r - radius, b); lineTo(r - arm, b)
            // bottom-left
            moveTo(l + arm, b); lineTo(l + radius, b)
            cubicTo(l, b, l, b, l, b - radius); lineTo(l, b - arm)
        }
        drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
    }
}

/** Copy of the private rule in FaceLivenessDetector.kt. */
private fun fitpassShouldShowInstruction(
    state: LivenessCheckState,
    challengeType: FaceLivenessChallengeType?
): Boolean =
    !(
        challengeType == FaceLivenessChallengeType.FaceMovementChallenge &&
            state == LivenessCheckState.Running.withFaceOvalPosition(FaceDetector.FaceOvalPosition.MATCHED)
        )
