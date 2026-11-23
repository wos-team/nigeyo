package jp.nigeyo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeScreen(
    strings: Language,
    languages: List<Language>,
    selectLanguage: (Language) -> Unit,
    settings: () -> Unit,
    practice: () -> Unit,
    manual: () -> Unit,
    sites: () -> Unit,
) {
    Screen(actions = {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            languages.forEach { language ->
                val selected = language.id == strings.id
                TextButton(
                    onClick = { selectLanguage(language) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = selected },
                ) {
                    Label(language.nativeName, size = 12, color = if (selected) Ink else Muted, bold = selected)
                }
            }
        }
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(strings["app.name"], size = 32, bold = true, modifier = Modifier.weight(1f))
            TextButton(onClick = settings) { Label(strings["home.settings"]) }
        }
        Eyebrow(strings["home.eyebrow"])
        Title(strings["home.title"])
        Panel(Orange) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Pictogram("protect-head", 60.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label(strings["home.practiceTitle"], size = 22, bold = true)
                    Label(strings["home.practiceBody"])
                }
            }
            ActionButton(strings["home.practiceStart"], onClick = practice)
        }
        HomeRow(strings["home.feltShaking"], strings["home.feltShakingBody"], "↗", manual)
        Eyebrow(strings["home.savedPlaces"])
        HomeRow(strings["home.destinations"], strings["home.destinationsBody"], "›", sites)
        Panel(GreenSoft) {
            Label(strings["home.offlineTitle"], size = 15, bold = true)
            Label(strings["android.offline"], size = 13, color = Muted)
        }
    }
}

@Composable
private fun HomeRow(title: String, body: String, accessory: String, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RowLabels(title, body, Modifier.weight(1f))
            Label(accessory, size = 20, color = Muted)
        }
    }
}

@Composable
internal fun SettingsScreen(
    strings: Language, onBack: () -> Unit, open: (Page) -> Unit,
) {
    Screen(navTitle = strings["home.settings"], backLabel = strings["android.back"], onBack = onBack) {
        HomeRow(strings["settings.language"], strings.nativeName, "›") { open(Page.Language) }
        HomeRow(strings["settings.savedPlaces"], strings["settings.savedPlacesBody"], "›") { open(Page.Sites) }
        HomeRow(strings["settings.lockScreen"], strings["android.guideChannel"], "›") { open(Page.LockScreen) }
        Panel(onClick = { open(Page.Regions) }) { Label(strings["regions.title"], bold = true) }
        HomeRow(strings["settings.updateLanguages"], strings["settings.updateLanguagesBody"], "›") { open(Page.LanguageUpdate) }
        HomeRow(strings["settings.demo"], strings["settings.demoBody"], "›") { open(Page.Demo) }
        HomeRow(strings["settings.privacy"], strings["settings.privacyBody"], "›") { open(Page.Privacy) }
        HomeRow(strings["settings.diagnostics"], strings["android.diagnosticsBody"], "›") { open(Page.Diagnostics) }
        Panel(onClick = { open(Page.Delete) }) { Label(strings["settings.deleteAll"], bold = true) }
    }
}

@Composable
internal fun LanguageScreen(
    strings: Language,
    languages: List<Language>,
    selectLanguage: (Language) -> Unit,
    onBack: () -> Unit,
    next: (() -> Unit)? = null,
) {
    Screen(navTitle = strings["settings.languageNav"], backLabel = strings["android.back"],
        onBack = onBack.takeIf { next == null },
        actions = next?.let { action -> { ActionButton(strings["onboarding.language.continue"], onClick = action) } },
    ) {
        if (next != null) {
            Title(strings["onboarding.language.title"])
            Label(strings["onboarding.language.body"], color = Muted)
        }
        Column(
            Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            languages.forEach { language ->
                RadioRow(language.nativeName, null, language.id == strings.id) { selectLanguage(language) }
            }
        }
        Label(strings["settings.languageNote"], size = 12, color = Muted)
    }
}

@Composable
internal fun PracticeScreen(
    strings: Language,
    selected: Scenario,
    selectScenario: (Scenario) -> Unit,
    changeWallpaper: Boolean,
    setChangeWallpaper: (Boolean) -> Unit,
    onBack: () -> Unit,
    start: () -> Unit,
) {
    Screen(
        navTitle = strings["practice.nav"],
        backLabel = strings["android.back"],
        onBack = onBack,
        actions = {
            ActionButton(strings[selected.startKey], background = Orange, onClick = start)
            Label(strings["practice.footnote"], size = 12, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        },
    ) {
        Badge(strings["training.badgeLong"])
        Eyebrow(strings["practice.eyebrow"])
        Title(strings["practice.title"])
        Label(strings["practice.body"], color = Muted)
        Column(
            Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Scenario.entries.forEach { scenario ->
                RadioRow(strings[scenario.titleKey], strings[scenario.bodyKey], scenario == selected) {
                    selectScenario(scenario)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .toggleable(changeWallpaper, role = Role.Switch, onValueChange = setChangeWallpaper)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowLabels(
                strings["android.wallpaper"],
                strings["android.wallpaperBody"],
                Modifier.weight(1f),
            )
            Switch(checked = changeWallpaper, onCheckedChange = null)
        }
        Panel {
            val pictogram = if (selected == Scenario.Tsunami) "tsunami-evacuate" else "protect-head"
            Pictogram(pictogram, 56.dp)
            Label(strings["android.practice"], color = Muted)
        }
    }
}

@Composable
private fun RadioRow(title: String, body: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (selected) Peach else Color.White)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp).heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (body == null) Label(title, size = 17, bold = true, modifier = Modifier.weight(1f))
        else RowLabels(title, body, Modifier.weight(1f))
        RadioButton(selected, onClick = null)
    }
}

@Composable
internal fun WaitingScreen(strings: Language, notificationsEnabled: Boolean, cancel: () -> Unit) {
    Screen(actions = {
        ActionButton(strings["waiting.cancel"], background = Color.White, onClick = cancel)
    }) {
        Badge(strings["training.badgeLong"])
        Box(
            Modifier.fillMaxWidth().heightIn(min = 180.dp)
                .background(Peach, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(68.dp, 112.dp).border(3.dp, Ink, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(Modifier.padding(bottom = 9.dp).size(20.dp, 3.dp).background(Ink, RoundedCornerShape(2.dp)))
            }
        }
        Eyebrow(strings["waiting.eyebrow"])
        Title(strings["android.waitTitle"])
        Label(strings["android.waitBody"], color = Muted)
        if (!notificationsEnabled) Label(strings["android.notificationsOff"], color = Muted)
    }
}
