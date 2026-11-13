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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Visual settings for the Fitpass scan screen. */
data class FitpassScanStyle(
    val accent: Color = Color(0xFFD6453D),
    val pageBackground: Color = Color.White,
    val subtitle: String = "Hold your head within the frame to complete the scan",
    val subtitleColor: Color = Color(0xFF6B6F76),
    val showRecordingIndicator: Boolean = false,
    val showCancelButton: Boolean = false,
    /** Oval edge colour while scanning. */
    val ovalScanColor: Color = Color(0xFFD6453D),
    /** Oval edge colour once AWS has finished the check. */
    val ovalSuccessColor: Color = Color(0xFF1FAA59)
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

    // Success sequence, started only after AWS's code has finished the check
    // (video sent, session closed): edge turns green, then the oval shrinks,
    // then we report completion to the app.
    var successPhase by remember(key) { mutableStateOf(SuccessPhase.NONE) }

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
                onChallengeComplete = { successPhase = SuccessPhase.GREEN },
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

    LaunchedEffect(successPhase) {
        when (successPhase) {
            SuccessPhase.GREEN -> {
                delay((RING_FADE_MS + GREEN_HOLD_MS).toLong())
                successPhase = SuccessPhase.SHRINK
            }
            SuccessPhase.SHRINK -> {
                delay(OVAL_ANIMATION_MS.toLong())
                currentOnChallengeComplete()
            }
            SuccessPhase.NONE -> Unit
        }
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

                    // Last step: shrink to a point at the oval's centre, only after
                    // the check finished and the edge has turned green.
                    val shrinking = successPhase == SuccessPhase.SHRINK && finalRect != null

                    val targetRect = when {
                        shrinking -> RectF(
                            finalRect!!.centerX(),
                            finalRect.centerY(),
                            finalRect.centerX(),
                            finalRect.centerY()
                        )
                        serverRect != null -> serverRect
                        // AWS cleared the oval while finishing: hold it where it was.
                        finalRect != null -> finalRect
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

                    // Our copy of AWS's FaceGuide mask, without its fixed grey outline,
                    // so the green edge is the only outline.
                    FitpassOvalMask(
                        modifier = Modifier
                            .fillMaxSize()
                            .align(Alignment.Center),
                        ovalRect = shownRect,
                        videoViewportSize = viewport,
                        backgroundColor = style.pageBackground
                    )
                    // Oval edge: red while scanning, fades to green once the check has finished.
                    val ringColor by animateColorAsState(
                        targetValue = if (successPhase != SuccessPhase.NONE) {
                            style.ovalSuccessColor
                        } else {
                            style.ovalScanColor
                        },
                        animationSpec = tween(durationMillis = RING_FADE_MS),
                        label = "fitpassRingColor"
                    )
                    Box(
                        modifier = Modifier
                            .size(viewport.viewportDpSize)
                            .align(Alignment.Center)
                    ) {
                        // While shrinking, the edge thins out with the oval and reaches zero together.
                        val ringScale = if (shrinking) {
                            (shownRect.width() / finalRect!!.width()).coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                        OvalProgressRing(
                            ovalRect = viewport.getScaledBoundingRect(shownRect),
                            color = ringColor,
                            alpha = 1f,
                            scale = ringScale,
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

            // Status: one grey line just above the tips. It shows AWS's message
            // ("Move closer", "Hold still"...) and falls back to the Fitpass subtitle.
            // Fixed height: if this area grew or shrank, the camera box above would
            // resize and the oval would visibly change size.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(STATUS_AREA_HEIGHT),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
            val checkState = livenessState.livenessCheckState
            val challengeType = livenessState.livenessSessionInfo?.challengeType

            // AWS's own string resource, so the wording is exactly AWS's.
            val awsMessageId: Int? = checkState.instructionId
            val awsMessage = if (fitpassShouldShowInstruction(checkState, challengeType)) {
                awsMessageId?.takeIf { it != 0 }?.let { stringResource(it) }
            } else {
                null
            }

            Text(
                text = awsMessage ?: style.subtitle,
                color = style.subtitleColor,
                fontSize = INSTRUCTION_TEXT_SIZE,
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

/** Size of the grey AWS message line above the tips. */
private val INSTRUCTION_TEXT_SIZE = 16.sp

/** AWS's own placeholder oval for the 480x640 preview (same values as AWS's start view). */
private val PLACEHOLDER_OVAL = RectF(120f, 126f, 360f, 514f)

private const val OVAL_ANIMATION_MS = 450

/** Width of the green oval edge, and how fast its colour follows the fit. */
private val RING_WIDTH = 5.dp
private const val RING_FADE_MS = 250

/** How long the edge stays fully green before the oval starts shrinking. */
private const val GREEN_HOLD_MS = 400

/** Steps of the success sequence after AWS finishes the check. */
private enum class SuccessPhase { NONE, GREEN, SHRINK }

/**
 * Same as AWS's FaceGuide (FaceGuide.kt), minus the grey 4dp oval stroke:
 * fills the area with the background colour and cuts the oval out, so the
 * camera shows only inside the oval. Drawing only; AWS's check is unaffected.
 */
@Composable
private fun FitpassOvalMask(
    modifier: Modifier,
    ovalRect: RectF,
    videoViewportSize: VideoViewportSize,
    backgroundColor: Color
) {
    val scaled = videoViewportSize.getScaledBoundingRect(ovalRect)

    Canvas(modifier.graphicsLayer(alpha = 0.99f)) {
        drawRect(color = backgroundColor, size = size)

        // Centre of the viewport inside the full-size canvas, as in AWS's FaceGuide
        val viewportTopLeft = Offset(
            x = (size.width - videoViewportSize.viewportPixelSize.width) / 2,
            y = (size.height - videoViewportSize.viewportPixelSize.height) / 2
        )
        val ovalTopLeft = Offset(
            x = viewportTopLeft.x + scaled.left,
            y = viewportTopLeft.y + scaled.top
        )
        val ovalSize = Size(
            width = scaled.right - scaled.left,
            height = scaled.bottom - scaled.top
        )

        // Cut the oval out of the background
        drawOval(
            color = Color.Transparent,
            style = Fill,
            topLeft = ovalTopLeft,
            size = ovalSize,
            blendMode = BlendMode.SrcOut
        )
    }
}

/** Oval edge drawn exactly on the oval; its colour fades in as the face fits. */
@Composable
private fun OvalProgressRing(
    ovalRect: RectF,
    color: Color,
    alpha: Float,
    modifier: Modifier = Modifier,
    scale: Float = 1f
) {
    val strokeWidth = with(LocalDensity.current) { RING_WIDTH.toPx() } * scale

    Canvas(modifier) {
        if (alpha <= 0.01f || scale <= 0.01f || ovalRect.width() <= 0f) return@Canvas
        drawOval(
            color = color.copy(alpha = alpha),
            topLeft = Offset(ovalRect.left, ovalRect.top),
            size = Size(ovalRect.width(), ovalRect.height()),
            style = Stroke(width = strokeWidth)
        )
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
