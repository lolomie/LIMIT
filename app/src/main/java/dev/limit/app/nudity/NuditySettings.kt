package dev.limit.app.nudity

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.limit.app.data.AppEntry

@Composable
fun NuditySettings(apps: List<AppEntry>, accessibilityConnected: Boolean) {
    val context = LocalContext.current
    val prefs = remember { NudityPreferences(context) }
    var aiEnabled by remember { mutableStateOf(prefs.aiEnabled) }
    var fallbackEnabled by remember { mutableStateOf(prefs.fallbackEnabled) }
    var chosen by remember { mutableStateOf(prefs.fallbackPackages) }
    var sensitivity by remember { mutableStateOf(prefs.sensitivity) }
    var showApps by remember { mutableStateOf(false) }
    val session by NuditySession.status.collectAsState()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            NuditySession.set(NudityPhase.CONSENT_REQUIRED, "Bildschirmfreigabe nicht erteilt.")
        } else {
            NuditySession.set(NudityPhase.STARTING)
            val intent = Intent(context, NudityDetectionService::class.java).apply {
                action = NudityDetectionService.START
                putExtra(NudityDetectionService.RESULT_CODE, result.resultCode)
                putExtra(NudityDetectionService.RESULT_DATA, result.data)
            }
            try { ContextCompat.startForegroundService(context, intent) }
            catch (_: Exception) {
                NuditySession.set(NudityPhase.ERROR, "Bildschirmaufnahme konnte nicht starten.")
            }
        }
    }
    fun stop() {
        context.stopService(Intent(context, NudityDetectionService::class.java))
        NuditySession.set(NudityPhase.INACTIVE)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Nacktheitsschutz", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("KI-Nacktheitsschutz", modifier = Modifier.weight(1f))
            Switch(checked = aiEnabled, onCheckedChange = {
                aiEnabled = it
                prefs.aiEnabled = it
                if (!it) stop() else NuditySession.set(NudityPhase.CONSENT_REQUIRED)
            }, modifier = Modifier.testTag("nudity_toggle"))
        }
        val label = when {
            !aiEnabled -> "Inaktiv"
            !accessibilityConnected && session.phase == NudityPhase.ACTIVE ->
                "Aufnahme aktiv, aber Accessibility-Sperre nicht verfügbar"
            session.phase == NudityPhase.ACTIVE -> "Aktiv – Aufnahme und Offline-Analyse laufen"
            session.phase == NudityPhase.STARTING -> "Wird gestartet"
            session.phase == NudityPhase.ERROR -> "Fehler"
            else -> "Freigabe erforderlich"
        }
        Text("Status: $label", style = MaterialTheme.typography.bodyMedium)
        session.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (aiEnabled && session.phase != NudityPhase.ACTIVE && session.phase != NudityPhase.STARTING) {
            Button(
                onClick = {
                    try {
                        val manager = context.getSystemService(MediaProjectionManager::class.java)
                        consent.launch(manager.createScreenCaptureIntent())
                    } catch (_: Exception) {
                        NuditySession.set(NudityPhase.ERROR, "Bildschirmfreigabe nicht verfügbar.")
                    }
                },
                modifier = Modifier.testTag("nudity_start"),
            ) { Text("Schutz starten") }
        }
        if (session.phase == NudityPhase.ACTIVE || session.phase == NudityPhase.STARTING) {
            OutlinedButton(onClick = { stop() }, modifier = Modifier.testTag("nudity_stop")) {
                Text("Schutz beenden")
            }
        }
        if (aiEnabled) {
            Text("Erkennungsstärke", style = MaterialTheme.typography.titleMedium)
            listOf(
                NuditySensitivity.STRICT to "Weniger Fehlalarme",
                NuditySensitivity.BALANCED to "Ausgewogen",
                NuditySensitivity.SENSITIVE to "Empfindlicher",
            ).forEach { (value, labelText) ->
                Row(Modifier.fillMaxWidth().clickable {
                    sensitivity = value
                    prefs.sensitivity = value
                }, horizontalArrangement = Arrangement.Start) {
                    RadioButton(selected = sensitivity == value, onClick = {
                        sensitivity = value
                        prefs.sensitivity = value
                    })
                    Text(labelText, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Ersatzschutz für gewählte Apps", modifier = Modifier.weight(1f))
            Switch(checked = fallbackEnabled, onCheckedChange = {
                fallbackEnabled = it
                prefs.fallbackEnabled = it
                NudityBlockCoordinator.clearRequest()
            }, modifier = Modifier.testTag("nudity_fallback_toggle"))
        }
        if (fallbackEnabled) {
            TextButton(onClick = { showApps = true }, modifier = Modifier.testTag("nudity_select_apps")) {
                Text("Apps auswählen (${chosen.size})")
            }
        }
        Text(
            "Nur während einer ausdrücklich freigegebenen Aufnahme werden Bildpunkte lokal analysiert. " +
                "Nichts wird gespeichert oder hochgeladen. Erkennung ist unvollständig, und geschützte " +
                "Bildschirme bleiben unsichtbar. Nach Neustart oder Drehung ist eine neue Freigabe nötig.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!accessibilityConnected) Text(
            "Accessibility ist nicht verbunden. Die Schutzoberfläche kann nicht zuverlässig erscheinen.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (showApps) {
        AlertDialog(
            onDismissRequest = { showApps = false },
            title = { Text("Ersatzschutz: Apps sperren") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(Modifier.fillMaxWidth().clickable {
                            chosen = if (app.packageName in chosen) chosen - app.packageName
                                     else chosen + app.packageName
                            prefs.fallbackPackages = chosen
                        }) {
                            Checkbox(checked = app.packageName in chosen, onCheckedChange = {
                                chosen = if (it) chosen + app.packageName else chosen - app.packageName
                                prefs.fallbackPackages = chosen
                            })
                            Text(app.label, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showApps = false }) { Text("Fertig") } },
        )
    }
}
