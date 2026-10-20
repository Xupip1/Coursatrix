package com.coursatrix.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coursatrix.app.ui.CoursatrixApp
import com.coursatrix.app.ui.CoursatrixViewModel
import com.coursatrix.app.ui.theme.CoursatrixTheme
import com.coursatrix.core.model.AccentColor
import com.coursatrix.core.model.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as CoursatrixApplication).repository
        setContent {
            val factory = remember { CoursatrixViewModel.factory(repository) }
            val model: CoursatrixViewModel = viewModel(factory = factory)
            val data by model.data.collectAsStateWithLifecycle()
            CoursatrixTheme(data?.preferences?.theme ?: ThemeMode.SYSTEM, data?.preferences?.accent ?: AccentColor.VIOLET) {
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                SideEffect {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                CoursatrixApp(model, data)
            }
        }
    }
}
