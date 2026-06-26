package com.demo.open_gl_example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import com.demo.open_gl_example.ui.theme.OpenGL_ExampleTheme
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * @author PengHaiChen
 * @date 2026/6/25 22:37:59
 * @description
 */
class ImageGlDisplayGestureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenGL_ExampleTheme {
                DisplayScreen(intent.getStringExtra(EXTRA_IMAGE_URI))
            }
        }
    }
}

@Composable
private fun DisplayScreen(imageUriString: String?) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val renderer = remember { GestureImageRenderer() }

    LaunchedEffect(imageUriString) {
        bitmap = null
        val uriString = imageUriString ?: return@LaunchedEffect
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
            bitmap = decodeAndFixBitmap(input)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (bitmap == null) {
                Text(text = "Loading image...")
            } else {
                GestureGlImageView(
                    bitmap = bitmap,
                    renderer = renderer,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun GestureGlImageView(
    bitmap: Bitmap?,
    renderer: GestureImageRenderer,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = {
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                setOnTouchListener(GestureTouchListener(this, renderer))
            }
        },
        update = {
            renderer.setBitmap(bitmap)
            it.requestRender()
        }
    )
}

private fun decodeAndFixBitmap(input: InputStream): Bitmap {
    val bytes = input.readBytes()
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalStateException("Failed to decode bitmap")

    val exif = ExifInterface(bytes.inputStream())
    return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> rotate(raw, 90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> rotate(raw, 180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> rotate(raw, 270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flip(raw, horizontal = true, vertical = false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> flip(raw, horizontal = false, vertical = true)
        else -> raw
    }
}

private fun rotate(bitmap: Bitmap, degree: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degree) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun flip(bitmap: Bitmap, horizontal: Boolean, vertical: Boolean): Bitmap {
    val matrix = Matrix().apply {
        postScale(if (horizontal) -1f else 1f, if (vertical) -1f else 1f)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private class GestureTouchListener(
    private val glView: GLSurfaceView,
    private val renderer: GestureImageRenderer
) : View.OnTouchListener {
    private var lastCentroidNdc: PointF? = null
    private var lastDistance = 0f
    private var lastAngle = 0f
    private var lastPointerCount = 0

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                rememberGesture(event)
            }

            MotionEvent.ACTION_MOVE -> {
                val width = glView.width.takeIf { it > 0 } ?: return true
                val height = glView.height.takeIf { it > 0 } ?: return true
                val pointerCount = event.pointerCount
                val centroid = centroidNdc(event, width, height)
                val previousCentroid = lastCentroidNdc
                if (previousCentroid != null) {
                    val panX = centroid.x - previousCentroid.x
                    val panY = centroid.y - previousCentroid.y
                    var scaleFactor = 1f
                    var rotationDelta = 0f

                    if (pointerCount >= 2 && lastPointerCount >= 2) {
                        val distance = pointerDistance(event)
                        if (lastDistance > 0f && distance > 0f) {
                            scaleFactor = distance / lastDistance
                        }
                        val angle = pointerAngle(event)
                        rotationDelta = shortestAngleDelta(angle, lastAngle)
                    }

                    renderer.applyGesture(
                        focalX = centroid.x,
                        focalY = centroid.y,
                        panX = panX,
                        panY = panY,
                        scaleFactor = scaleFactor,
                        rotationDeltaDegrees = rotationDelta
                    )
                    glView.requestRender()
                }
                rememberGesture(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastCentroidNdc = null
                lastDistance = 0f
                lastAngle = 0f
                lastPointerCount = 0
            }
        }
        return true
    }

    private fun rememberGesture(event: MotionEvent) {
        val width = glView.width.takeIf { it > 0 } ?: return
        val height = glView.height.takeIf { it > 0 } ?: return
        val ignoredPointer = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            event.actionIndex
        } else {
            -1
        }
        val activePointerCount = event.pointerCount - if (ignoredPointer >= 0) 1 else 0
        if (activePointerCount <= 0) {
            lastCentroidNdc = null
            lastPointerCount = 0
            return
        }

        lastCentroidNdc = centroidNdc(event, width, height, ignoredPointer)
        lastPointerCount = activePointerCount
        if (activePointerCount >= 2) {
            lastDistance = pointerDistance(event, ignoredPointer)
            lastAngle = pointerAngle(event, ignoredPointer)
        } else {
            lastDistance = 0f
            lastAngle = 0f
        }
    }

    private fun centroidNdc(
        event: MotionEvent,
        width: Int,
        height: Int,
        ignoredPointer: Int = -1
    ): PointF {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == ignoredPointer) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        val x = (sumX / count / width) * 2f - 1f
        val y = 1f - (sumY / count / height) * 2f
        return PointF(x, y)
    }

    private fun pointerDistance(event: MotionEvent, ignoredPointer: Int = -1): Float {
        val pointers = firstTwoPointers(event, ignoredPointer)
        if (pointers.size < 2) return 0f
        val dx = event.getX(pointers[1]) - event.getX(pointers[0])
        val dy = event.getY(pointers[1]) - event.getY(pointers[0])
        return hypot(dx, dy)
    }

    private fun pointerAngle(event: MotionEvent, ignoredPointer: Int = -1): Float {
        val pointers = firstTwoPointers(event, ignoredPointer)
        if (pointers.size < 2) return 0f
        val dx = event.getX(pointers[1]) - event.getX(pointers[0])
        val dy = event.getY(pointers[0]) - event.getY(pointers[1])
        return Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    private fun firstTwoPointers(event: MotionEvent, ignoredPointer: Int): IntArray {
        val result = IntArray(2)
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == ignoredPointer) continue
            result[count] = i
            count++
            if (count == 2) break
        }
        return result.copyOf(count)
    }

    private fun shortestAngleDelta(current: Float, previous: Float): Float {
        var delta = current - previous
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return delta
    }
}

private class GestureImageRenderer : GLSurfaceView.Renderer {
    @Volatile
    private var bitmap: Bitmap? = null

    @Volatile
    private var bitmapDirty = false

    private val transformLock = Any()
    private var textureId = 0
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var samplerHandle = 0
    private var imageAspect = 1f
    private var viewAspect = 1f
    private var baseHalfWidth = 1f
    private var baseHalfHeight = 1f
    private var scale = MIN_SCALE
    private var rotationDegrees = 0f
    private var translationX = 0f
    private var translationY = 0f
    private var vertexBuffer: FloatBuffer = makeBuffer(fullVertices())

    fun setBitmap(newBitmap: Bitmap?) {
        synchronized(transformLock) {
            if (bitmap === newBitmap && !bitmapDirty) return
            bitmap = newBitmap
            bitmapDirty = true
            if (newBitmap != null && newBitmap.height != 0) {
                imageAspect = newBitmap.width.toFloat() / newBitmap.height.toFloat()
            }
            resetTransformLocked()
            updateBaseSizeLocked()
            updateVertexBufferLocked()
        }
    }

    fun applyGesture(
        focalX: Float,
        focalY: Float,
        panX: Float,
        panY: Float,
        scaleFactor: Float,
        rotationDeltaDegrees: Float
    ) {
        synchronized(transformLock) {
            val focalWorldX = focalX * viewAspect
            val focalWorldY = focalY
            val panWorldX = panX * viewAspect
            val panWorldY = panY
            val oldScale = scale
            scale = (scale * scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            val appliedScaleFactor = if (oldScale != 0f) scale / oldScale else 1f

            val radians = Math.toRadians(rotationDeltaDegrees.toDouble())
            val cosValue = cos(radians).toFloat()
            val sinValue = sin(radians).toFloat()
            val previousFocalX = focalWorldX - panWorldX
            val previousFocalY = focalWorldY - panWorldY
            val dx = translationX - previousFocalX
            val dy = translationY - previousFocalY

            translationX = focalWorldX + appliedScaleFactor * (cosValue * dx - sinValue * dy)
            translationY = focalWorldY + appliedScaleFactor * (sinValue * dx + cosValue * dy)
            rotationDegrees = normalizeDegrees(rotationDegrees + rotationDeltaDegrees)

            clampTranslationLocked()
            updateVertexBufferLocked()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        if (height != 0) {
            synchronized(transformLock) {
                viewAspect = width.toFloat() / height.toFloat()
                updateBaseSizeLocked()
                clampTranslationLocked()
                updateVertexBufferLocked()
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0.08f, 0.08f, 0.08f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val currentBitmap = bitmap
        if (bitmapDirty && currentBitmap != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, currentBitmap, 0)
            bitmapDirty = false
        }

        if (textureId == 0 || currentBitmap == null) return

        val buffer = synchronized(transformLock) { vertexBuffer }
        GLES20.glUseProgram(program)
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, buffer)

        buffer.position(2)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, buffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun resetTransformLocked() {
        scale = MIN_SCALE
        rotationDegrees = 0f
        translationX = 0f
        translationY = 0f
    }

    private fun updateBaseSizeLocked() {
        val (xScale, yScale) = if (imageAspect > viewAspect) {
            viewAspect to viewAspect / imageAspect
        } else {
            imageAspect to 1f
        }
        baseHalfWidth = xScale
        baseHalfHeight = yScale
    }

    private fun clampTranslationLocked() {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cosValue = abs(cos(radians).toFloat())
        val sinValue = abs(sin(radians).toFloat())
        val extentX = (cosValue * baseHalfWidth + sinValue * baseHalfHeight) * scale
        val extentY = (sinValue * baseHalfWidth + cosValue * baseHalfHeight) * scale
        val viewportHalfWidth = viewAspect

        translationX = if (extentX <= viewportHalfWidth) {
            0f
        } else {
            translationX.coerceIn(viewportHalfWidth - extentX, extentX - viewportHalfWidth)
        }
        translationY = if (extentY <= 1f) {
            0f
        } else {
            translationY.coerceIn(1f - extentY, extentY - 1f)
        }
    }

    private fun updateVertexBufferLocked() {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cosValue = cos(radians).toFloat()
        val sinValue = sin(radians).toFloat()

        fun transformX(x: Float, y: Float): Float {
            val scaledX = x * scale
            val scaledY = y * scale
            return (scaledX * cosValue - scaledY * sinValue + translationX) / viewAspect
        }

        fun transformY(x: Float, y: Float): Float {
            val scaledX = x * scale
            val scaledY = y * scale
            return scaledX * sinValue + scaledY * cosValue + translationY
        }

        val left = -baseHalfWidth
        val right = baseHalfWidth
        val bottom = -baseHalfHeight
        val top = baseHalfHeight
        vertexBuffer = makeBuffer(
            floatArrayOf(
                transformX(left, bottom), transformY(left, bottom), 0f, 1f,
                transformX(right, bottom), transformY(right, bottom), 1f, 1f,
                transformX(left, top), transformY(left, top), 0f, 0f,
                transformX(right, top), transformY(right, top), 1f, 0f
            )
        )
    }

    private fun normalizeDegrees(value: Float): Float {
        var normalized = value
        while (normalized > 180f) normalized -= 360f
        while (normalized < -180f) normalized += 360f
        return normalized
    }

    private fun createProgram(vertexShaderSource: String, fragmentShaderSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)
        return GLES20.glCreateProgram().also { programId ->
            GLES20.glAttachShader(programId, vertexShader)
            GLES20.glAttachShader(programId, fragmentShader)
            GLES20.glLinkProgram(programId)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }
    }

    private fun makeBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

    private fun fullVertices(): FloatArray = floatArrayOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f
    )

    private companion object {
        const val MIN_SCALE = 0.2f
        const val MAX_SCALE = 5f

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
