package com.demo.open_gl_example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Bundle
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

class ImageGlDisplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenGL_ExampleTheme {
                ImageGlDisplayScreen(intent.getStringExtra(EXTRA_IMAGE_URI))
            }
        }
    }

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
    }
}

@Composable
private fun ImageGlDisplayScreen(imageUriString: String?) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val renderer = remember { ImageRenderer() }

    LaunchedEffect(imageUriString) {
        bitmap = null
        val uriString = imageUriString ?: return@LaunchedEffect
        val uri = android.net.Uri.parse(uriString)
        context.contentResolver.openInputStream(uri)?.use { input ->
            bitmap = decodeAndFixBitmap(input)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (bitmap == null) {
                Text(text = "图片加载中...")
            } else {
                GlImageView(bitmap = bitmap, renderer = renderer, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun GlImageView(
    bitmap: Bitmap?,
    renderer: ImageRenderer,
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
                renderer.attach(this)
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
    val oriented = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> rotate(raw, 90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> rotate(raw, 180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> rotate(raw, 270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flip(raw, horizontal = true, vertical = false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> flip(raw, horizontal = false, vertical = true)
        else -> raw
    }
    return oriented
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

private class ImageRenderer : GLSurfaceView.Renderer {
    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var bitmapDirty = false
    private var textureId = 0
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var samplerHandle = 0
    private var imageAspect = 1f
    private var viewAspect = 1f
    private var vertexBuffer: FloatBuffer = makeBuffer(fullVertices())

    fun attach(view: GLSurfaceView) = Unit

    fun setBitmap(newBitmap: Bitmap?) {
        bitmap = newBitmap
        bitmapDirty = true
        if (newBitmap != null && newBitmap.height != 0) {
            imageAspect = newBitmap.width.toFloat() / newBitmap.height.toFloat()
            updateVertices()
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
            viewAspect = width.toFloat() / height.toFloat()
            updateVertices()
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

        GLES20.glUseProgram(program)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun updateVertices() {
        val (xScale, yScale) = if (imageAspect > viewAspect) {
            1f to viewAspect / imageAspect
        } else {
            imageAspect / viewAspect to 1f
        }
        vertexBuffer = makeBuffer(floatArrayOf(
            -xScale, -yScale, 0f, 0f,
            xScale, -yScale, 1f, 0f,
            -xScale, yScale, 0f, 1f,
            xScale, yScale, 1f, 1f
        ))
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

    private fun updateVerticesIfNeeded() {
        updateVertices()
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
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    private companion object {
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
