package jp.nigeyo.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun GuideScreen(
    strings: Language,
    phase: GuidePhase,
    cards: List<ActionCard>,
    training: Boolean,
    wallpaperUpdateFailed: Boolean,
    confirmShaking: () -> Unit,
    openEvacuation: () -> Unit,
    confirmEnd: () -> Unit,
) {
    val background = when (phase) {
        GuidePhase.Protect -> Peach
        GuidePhase.Checks -> Paper
        GuidePhase.Tsunami -> Orange
    }
    val tsunami = phase == GuidePhase.Tsunami

    Screen(background = background, actions = {
        if (phase == GuidePhase.Protect) {
            ActionButton(strings["guide.shakingQuestion"], onClick = confirmShaking)
            Label(strings["guide.shakingHint"], size = 12, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            val key = if (tsunami) "guide.tsunami.checkPlaces" else "guide.unsafeButton"
            ActionButton(strings[key], onClick = openEvacuation)
        }
        ActionButton(
            strings[if (training) "training.end" else "manual.end"],
            background = Color.White,
            onClick = confirmEnd,
        )
    }) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label(strings["app.name"], size = 22, bold = true, modifier = Modifier.weight(1f))
            if (training) Badge(strings["training.badge"], dark = phase != GuidePhase.Protect)
        }

        when (phase) {
            GuidePhase.Checks -> {
                Eyebrow(strings["guide.checks.step"])
                Title(strings["guide.checks.title"])
                cards.forEach { card ->
                    Panel {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Pictogram(card.pictogram, 56.dp, label = strings[card.accessibilityKey])
                            RowLabels(strings[card.titleKey], strings[card.bodyKey], Modifier.weight(1f))
                        }
                    }
                }
            }
            GuidePhase.Protect, GuidePhase.Tsunami -> {
                val card = cards.single()
                Eyebrow(strings[if (tsunami) "guide.tsunami.step" else "guide.protect.step"], if (tsunami) Ink else Muted)
                Title(strings[card.titleKey], size = if (tsunami) 32 else 28)
                Pictogram(
                    name = card.pictogram, size = 180.dp, label = strings[card.accessibilityKey],
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp),
                )
                Label(strings[card.bodyKey], size = 16)
                if (tsunami && training) {
                    Panel(Peach) {
                        Label(strings["guide.tsunami.drillTitle"], bold = true)
                        Label(strings["guide.tsunami.drillBody"], size = 12)
                    }
                }
            }
        }
        if (wallpaperUpdateFailed) {
            Panel {
                Label(strings["android.wallpaperFailed"], color = Muted)
            }
        }
    }
}
