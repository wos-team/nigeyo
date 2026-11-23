package jp.nigeyo.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun LockScreenSettings(
    strings: Language,
    controller: TrainingController,
    back: () -> Unit,
    requestNotifications: () -> Unit,
    finish: (() -> Unit)? = null,
    test: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Screen(navTitle = strings["onboarding.lock.nav"], backLabel = strings["android.back"], onBack = back,
        actions = finish?.let { callback -> { ActionButton(strings["onboarding.lock.finish"], onClick = callback) } },
    ) {
        Title(strings["onboarding.lock.title"])
        Panel {
            RowLabels(
                strings["settings.lockScreen"], strings["android.lockScreenBody"],
            )
        }
        Panel {
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Label(strings["android.remoteWallpaper"], modifier = Modifier.weight(1f), bold = true)
                Switch(controller.remoteWallpaper, controller::changeRemoteWallpaperSetting)
            }
            Label(strings["android.remoteWallpaperBody"], color = Muted)
        }
        Panel {
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Label(strings["android.guideChannel"], modifier = Modifier.weight(1f), bold = true)
                Switch(controller.showLockScreenGuide, controller::setLockScreenGuide)
            }
        }
        Label(strings[if (controller.notificationsEnabled) {
            "onboarding.lock.notificationsAllowed"
        } else {
            "android.notificationsOff"
        }], color = Muted)
        if (!controller.notificationsEnabled) {
            ActionButton(strings["android.allowNotifications"], onClick = requestNotifications)
        }
        ActionButton(strings["home.settings"], background = Color.White) {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        test?.let { ActionButton(strings["onboarding.lock.testNotification"], onClick = it) }
    }
}

@Composable
internal fun PrivacyScreen(strings: Language, back: () -> Unit) {
    Screen(navTitle = strings["privacy.nav"], backLabel = strings["android.back"], onBack = back) {
        Panel { RowLabels(strings["privacy.savedTitle"], strings["privacy.savedBody"]) }
        Panel { RowLabels(strings["privacy.notSavedTitle"], strings["privacy.notSavedBody"]) }
        Panel { RowLabels(strings["privacy.mapsTitle"], strings["android.privacyMaps"]) }
    }
}

@Composable
internal fun DeleteScreen(strings: Language, back: () -> Unit, erase: () -> Unit) {
    Screen(navTitle = strings["delete.nav"], backLabel = strings["android.back"], onBack = back,
        actions = {
            ActionButton(strings["delete.confirm"], onClick = erase)
            ActionButton(strings["common.cancel"], background = Color.White, onClick = back)
        },
    ) {
        Title(strings["delete.title"])
        Label(strings["android.deleteBody"])
        Label(strings["android.remoteWallpaperBody"], color = Muted)
    }
}

@Composable
internal fun DiagnosticScreen(strings: Language, controller: TrainingController, back: () -> Unit) {
    val context = LocalContext.current
    val report = (listOf(
        "nigeyo · Android ${android.os.Build.VERSION.RELEASE}",
        "language: ${strings.id}",
        "notifications: ${controller.notificationsEnabled}",
        "guide: ${controller.phase?.name ?: "idle"}",
        "wallpaperError: ${controller.wallpaperUpdateFailed}",
        "demo: ${controller.demo.state}",
        "regionalAlerts: ${controller.alerts.enabled}",
        "regionalRegistrationSynced: ${controller.alerts.synced}",
    ) + controller.diagnostics).joinToString("\n")
    Screen(navTitle = strings["settings.diagnostics"], backLabel = strings["android.back"], onBack = back) {
        Panel { Label(report) }
        controller.alerts.installationId?.let { id ->
            Panel {
                Label(strings["android.installationId"], bold = true)
                Label(id)
                ActionButton(strings["common.copy"], background = Color.White) {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("nigeyo", id))
                }
            }
        }
        ActionButton(strings["common.copy"]) {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("nigeyo", report),
            )
        }
        if (controller.session != null) {
            ActionButton(strings["settings.resetSession"], background = Color.White, onClick = controller::end)
        }
    }
}
