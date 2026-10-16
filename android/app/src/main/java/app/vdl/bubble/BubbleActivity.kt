package app.vdl.bubble

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vdl.MainViewModel
import app.vdl.ui.BubblePanel
import app.vdl.ui.VdlTheme

/**
 * Panneau ouvert par la bulle, par-dessus l'app en cours (Facebook, TikTok…).
 * Il lit le lien copié, propose format et qualité, lance le téléchargement puis se ferme :
 * l'utilisateur retrouve l'app où il était.
 */
class BubbleActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var clipboardChecked = false
    private var noLink by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VdlTheme {
                val probe by vm.probe.collectAsState()
                val message by vm.message.collectAsState()
                LaunchedEffect(message) {
                    message?.let {
                        Toast.makeText(this@BubbleActivity, it, Toast.LENGTH_SHORT).show()
                        vm.consumeMessage()
                        if (it.startsWith("Téléchargement lancé") || it.endsWith("ajoutés")) finish()
                    }
                }
                BubblePanel(
                    probe = probe,
                    noLink = noLink,
                    onAnalyze = { noLink = false; vm.analyze(it) },
                    onDownload = vm::download,
                    onChoose = vm::analyzeMedia,
                    onClose = ::finish,
                )
            }
        }
    }

    /** Android ne donne accès au presse-papiers qu'une fois la fenêtre au premier plan. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || clipboardChecked) return
        clipboardChecked = true
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val url = MainViewModel.extractUrl(clip)
        if (url != null) vm.analyze(url) else noLink = true
    }
}
