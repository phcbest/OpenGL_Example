package com.demo.open_gl_example;

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.demo.open_gl_example.ui.theme.OpenGL_ExampleTheme

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

}
