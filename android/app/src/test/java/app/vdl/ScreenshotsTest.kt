package app.vdl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.vdl.ui.DownloadActions
import app.vdl.ui.DownloadsContent
import app.vdl.ui.HomeContent
import app.vdl.ui.ProbeContent
import app.vdl.ui.ShieldsContent
import app.vdl.ui.ShieldsInfo
import app.vdl.ui.StartPage
import app.vdl.ui.VdlTheme
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/**
 * Captures d'écran des interfaces, sans téléphone :  gradlew recordPaparazziDebug
 * Images produites dans app/src/test/snapshots/images/.
 */
class ScreenshotsTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    private val noActions = DownloadActions({}, {}, {}, {}, {})

    private val samples = listOf(
        DownloadItem("1", "u", "Comment cuisiner des pâtes parfaites en 10 minutes", "video", "1080",
            status = Status.RUNNING, progress = 62f, eta = 34, speed = "3.20MiB/s", size = "148.50MiB"),
        DownloadItem("2", "u", "Concert live — session acoustique", "audio", "best",
            status = Status.DONE, progress = 100f, size = "8.40MiB", fileName = "concert.mp3"),
        DownloadItem("3", "u", "Tutoriel Kotlin pour débutants", "video", "720", status = Status.QUEUED),
        DownloadItem("4", "u", "Vidéo privée", "video", "best", status = Status.ERROR,
            error = "Cette vidéo est privée ou a été supprimée."),
    )

    private fun shoot(dark: Boolean, content: @Composable () -> Unit) {
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
    private fun Home(clip: Boolean) = HomeContent(
        url = "", onUrlChange = {}, onSubmit = {}, onPaste = {},
        clipSuggestion = if (clip) "https://www.tiktok.com/@chef/video/7350000000000000000" else null,
        onUseClip = {}, engineReady = false, appUpdate = null, updateProgress = null, onUpdate = {},
        recent = samples, onSeeAll = {}, onOpenSite = {}, onOpenBrowser = {},
    )

    @Test fun home_light() = shoot(false) { Home(clip = true) }
    @Test fun home_dark() = shoot(true) { Home(clip = false) }

    @Test fun downloads_light() = shoot(false) { DownloadsContent(samples, noActions, onClearFinished = {}) }
    @Test fun downloads_dark() = shoot(true) { DownloadsContent(samples, noActions, onClearFinished = {}) }
    @Test fun downloads_empty() = shoot(false) { DownloadsContent(emptyList(), noActions, onClearFinished = {}) }

    @Test fun browser_start() = shoot(false) { StartPage(onOpen = {}) }

    @Test fun shields_light() = shoot(false) {
        ShieldsContent(ShieldsInfo("streaming-exemple.com", true, 47, 3, 2, 112_480), onToggle = {})
    }

    private val ready = ProbeState.Ready(
        ProbeResult("u", "Comment cuisiner des pâtes parfaites en 10 minutes", "", "Youtube", "Chef Marco", 612.0,
            listOf("best", "2160", "1440", "1080", "720", "480", "360"), false, emptyList()),
        emptyMap(), null,
    )

    @Test fun sheet_ready() = shoot(false) {
        Box(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) { ProbeContent(ready, { _, _, _, _ -> }, { _, _ -> }, {}) }
        }
    }

    @Test fun sheet_choose_dark() = shoot(true) {
        Column {
            ProbeContent(
                ProbeState.Choose(listOf(
                    DetectedMedia("https://cdn.example.com/v/master.m3u8?t=1", DetectedMedia.Kind.HLS, emptyMap(), "p"),
                    DetectedMedia("https://cdn.example.com/v/index-v1.m3u8?t=1", DetectedMedia.Kind.HLS, emptyMap(), "p"),
                    DetectedMedia("https://cdn.example.com/trailer.mp4", DetectedMedia.Kind.VIDEO, emptyMap(), "p"),
                ), "Page"),
                { _, _, _, _ -> }, { _, _ -> }, {},
            )
        }
    }
}
