package com.example.audioeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.audioeditor.ui.theme.AudioEditorTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioEditorTheme {
                Surface(Modifier.fillMaxSize()) {
                    EditorScreen(vm)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.stop() // pause playback when the app goes to the background
        if (vm.clips.isNotEmpty()) {
            vm.saveProject() // auto-save active project on pause
        }
    }
}
