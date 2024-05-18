package com.skyd.anivu.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.skyd.anivu.base.BaseComposeActivity
import com.skyd.anivu.ext.savePictureToMediaStore
import com.skyd.anivu.ui.component.showToast
import com.skyd.anivu.ui.mpv.PlayerView
import com.skyd.anivu.ui.mpv.copyAssetsForMpv
import java.io.File


class PlayActivity : BaseComposeActivity() {
    companion object {
        const val VIDEO_URI_KEY = "videoUri"
    }


    private lateinit var picture: File
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            picture.savePictureToMediaStore(this)
        } else {
            getString(com.skyd.anivu.R.string.player_no_permission_cannot_save_screenshot).showToast()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        copyAssetsForMpv(this)

        super.onCreate(savedInstanceState)

        val windowInsetsController =
            WindowCompat.getInsetsController(window, window.decorView)
        // Configure the behavior of the hidden system bars.
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        window.decorView.setOnApplyWindowInsetsListener { view, windowInsets ->
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            view.onApplyWindowInsets(windowInsets)
        }
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentBase {
            var videoUri by remember { mutableStateOf(handleIntent(intent)) }

            DisposableEffect(Unit) {
                val listener = Consumer<Intent> { newIntent ->
                    videoUri = handleIntent(newIntent)
                }
                addOnNewIntentListener(listener)
                onDispose { removeOnNewIntentListener(listener) }
            }
            videoUri?.let { uri ->
                PlayerView(
                    uri = uri,
                    onBack = { finish() },
                    onSaveScreenshot = {
                        picture = it
                        saveScreenshot()
                    }
                )
            }
        }
    }

    private fun handleIntent(intent: Intent?): Uri? {
        intent ?: return null
        return IntentCompat.getParcelableExtra(intent, VIDEO_URI_KEY, Uri::class.java)
            ?: intent.data
    }

    private fun saveScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            picture.savePictureToMediaStore(this)
        } else {
            requestPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}