package app.vdl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.vdl.ui.AddressBarContent
import app.vdl.ui.DownloadActions
import app.vdl.ui.DownloadFab
import app.vdl.ui.DownloadsContent
import app.vdl.ui.HomeContent
import app.vdl.ui.NoticeBanner
import app.vdl.ui.ProbeContent
import app.vdl.ui.ShieldsContent
import app.vdl.ui.ShieldsInfo
import app.vdl.ui.VdlBottomBar
import app.vdl.ui.VdlTheme
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/**
 * Captures d'écran complètes pour le site (site/assets/screens) :
 *   gradlew recordPaparazziDebug  puis  python tools/site_screens.py
 */
class SiteShotsTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    private val samples = listOf(
        DownloadItem("1", "u", "Recette : pâtes fraîches maison", "video", "1080",
            status = Status.RUNNING, progress = 62f, eta = 34, speed = "3.20MiB/s", size = "148.50MiB"),
        DownloadItem("2", "u", "Session acoustique en studio", "audio", "best",
            status = Status.DONE, progress = 100f, size = "8.40MiB"),
        DownloadItem("3", "u", "Cours de guitare — leçon 3", "video", "720", status = Status.DONE, size = "96.10MiB"),
        DownloadItem("4", "u", "Documentaire nature (playlist)", "video", "best", status = Status.QUEUED),
    )

    private fun shoot(dark: Boolean = false, content: @Composable () -> Unit) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_6.copy(nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT),
        )
        paparazzi.snapshot {
            VdlTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    @Composable
    private fun WithBar(tab: Tab, active: Int = 1, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            VdlBottomBar(tab, {}, active, browserDot = false)
        }
    }

    /** Page web factice : un lecteur vidéo et un peu de texte. */
    @Composable
    private fun FakeVideoPage() {
        Column(Modifier.fillMaxSize().background(Color.White)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF1B2A49), Color(0xFF3E5C8A)))),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth(0.35f).fillMaxHeight().background(Color(0xFFE53935)))
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Recette : pâtes fraîches maison", style = MaterialTheme.typography.titleMedium, color = Color(0xFF15171C))
                Text("12 k vues · il y a 2 jours", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7080))
                repeat(5) { i ->
                    Box(Modifier.fillMaxWidth(if (i % 2 == 0) 1f else 0.8f).height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFFE9ECF2)))
                }
            }
        }
    }

    @Composable
    private fun Browser(blocked: Int, overlay: @Composable () -> Unit = {}) {
        Column(Modifier.fillMaxSize()) {
            AddressBarContent(
                url = "https://video-exemple.com/regarder/42", canGoBack = true, canGoForward = false, progress = 100,
                shieldsOn = true, blockedTotal = blocked, onBack = {}, onForward = {}, onReload = {}, onLoad = {}, onShields = {},
            )
            Box(Modifier.weight(1f)) {
                FakeVideoPage()
                overlay()
            }
        }
    }

    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Browser(blocked = 23)
            Box(Modifier.fillMaxSize().background(Color(0x66000000)))
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(32.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    content()
                }
            }
        }
    }

    @Test fun site_1_home() = shoot {
        WithBar(Tab.HOME) {
            HomeContent(
                url = "", onUrlChange = {}, onSubmit = {}, onPaste = {},
                clipSuggestion = "https://video-exemple.com/regarder/42", onUseClip = {}, engineReady = true,
                appUpdate = null, updateProgress = null, onUpdate = {}, recent = samples, onSeeAll = {}, onOpenSite = {}, onOpenBrowser = {},
            )
        }
    }

    @Test fun site_2_browser() = shoot {
        WithBar(Tab.BROWSER) {
            Browser(blocked = 23) {
                Box(Modifier.fillMaxSize()) {
                    NoticeBanner("Pop-up bloquée : pub-exemple.com", onAction = {},
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth())
                    Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                        DownloadFab("Télécharger cette vidéo", 0) {}
                    }
                }
            }
        }
    }

    @Test fun site_3_quality() = shoot {
        Sheet {
            ProbeContent(
                ProbeState.Ready(
                    ProbeResult("u", "Recette : pâtes fraîches maison", "", "", "Chef Marco", 612.0,
                        listOf("best", "2160", "1080", "720", "480", "360"), false, emptyList()),
                    emptyMap(), null,
                ),
                { _, _, _, _ -> }, { _, _ -> }, {},
            )
        }
    }

    @Test fun site_4_shields() = shoot {
        Sheet { ShieldsContent(ShieldsInfo("video-exemple.com", true, 18, 3, 2, 72_525), onToggle = {}) }
    }

    @Test fun site_5_downloads() = shoot {
        WithBar(Tab.DOWNLOADS, active = 2) { DownloadsContent(samples, DownloadActions({}, {}, {}, {}, {}), onClearFinished = {}) }
    }

    @Test fun site_6_home_dark() = shoot(dark = true) {
        WithBar(Tab.HOME) {
            HomeContent(
                url = "", onUrlChange = {}, onSubmit = {}, onPaste = {}, clipSuggestion = null, onUseClip = {},
                engineReady = true, appUpdate = null, updateProgress = null, onUpdate = {}, recent = samples, onSeeAll = {},
                onOpenSite = {}, onOpenBrowser = {},
            )
        }
    }
}
