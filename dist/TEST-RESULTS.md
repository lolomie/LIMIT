# LIMIT 1.0.6 — immer verfügbare Dienstdiagnose

Dieses Diagnose-Update behebt das Ausblenden des Diagnoseabschnitts bei leerem Fehlerprotokoll. Die Ursache des wiederkehrenden Dienstausfalls auf dem Redmi ist weiterhin offen. Diese APK wird nicht als bestätigte Behebung des Dienstausfalls ausgeliefert.

- APK-Build: BESTANDEN; Version 1.0.6, Versionscode 7.
- JVM-Tests: 95 bestanden, keine Fehler oder übersprungenen Tests.
- Android-Tests: abschließend 8 bestanden, keine Fehler oder übersprungenen Tests (API29-Emulator).
- Lint: keine Fehler; 12 Hinweise im vollständigen Bericht.
- Signatur und Berechtigungen identisch mit 1.0.5; APK-Ausrichtung bestanden.
- Unabhängiges Review: keine konkreten Datenschutz-, Threading- oder Kompatibilitätsfehler gefunden.

## Änderung

Service diagnostics ist unabhängig vom Vorhandensein einer Fehlerdatei immer sichtbar. Kopieren erzeugt einen frischen Bericht mit installierter Version, Zeit, API, eigenen Fehleraufzeichnungen und aktuellem Nutzungszugriff-/Accessibility-/Verbindungsstatus. Auf Android 11+ werden höchstens drei von Android bereitgestellte Prozess-Endgründe für LIMIT ergänzt: Zeitpunkt, numerischer Grund/Status und eine feste Grundbezeichnung. Historische Einträge können aus einer älteren installierten Version stammen. Android 8–10 liefert einen verständlichen Hinweis auf die nicht verfügbare Systemfunktion.

Keine fremden Pakete, Fehlermeldungen, Systembeschreibungen, Trace-Dateien, Bildschirminhalte oder Nutzungsdaten werden zur Prozessdiagnose abgefragt oder kopiert. Keine neuen Berechtigungen oder Bibliotheken, kein automatischer Versand. Die unveränderten lokalen Fehlerdateien enthalten weiterhin höchstens drei bereinigte Einträge bis 16 KiB.

Die fünf zusätzlichen JVM-Tests prüfen leere Historie, Begrenzung/Sortierung und ANR-Bezeichnung, Fehler beim Systemzugriff ohne Nachrichtenleck, unbekannte Gründe ohne unbegründete Herstellerzuordnung sowie API29 ohne Aufruf der API30-Funktion. Der neue Android-Test prüft Sichtbarkeit und echtes Kopieren ohne notwendige gespeicherte Fehlerdatei. Alle bisherigen Sperr-/Ausnahme-/Persistenztests werden wiederholt.

## Umfang und Grenzen

Quellvergleich mit dem archivierten 1.0.5-Projekt: Nur ServiceDiagnostics.kt und LimitApp.kt sind unter den Produktionsquellen geändert; Dienst, Minutenanzeige, Zählung, Limits, Ausnahmen und Manifest sind identisch. Versionsangaben wurden angepasst.

Es war kein physisches Redmi für das Auslesen seiner Systemhistorie verfügbar. Eine fehlende Android-Historie schließt weder einen Absturz noch eine Beendigung durch Android/HyperOS aus. UNKNOWN/OTHER wird nicht automatisch als HyperOS-Fehler ausgelegt. Es wurde kein erneuter 15-Minuten-Dauerlauf behauptet.

Nach dem Update ohne Deinstallation: Nach einem erneuten Ausfall LIMIT → Settings → Service diagnostics → Copy service diagnostics und den Text zur weiteren Fehleranalyse verwenden. Android kann einen deaktivierten Accessibility-Dienst nicht durch diese Diagnosefunktion wieder aktivieren.

Nachweise: v106-build.log, v106-connected.log, aktuelle XML-/HTML-Berichte, v106-review.txt, v106-scope.json, v106-signature.txt, v106-alignment.txt, v106-permissions.txt und v106-upgrade.txt.

Der Softwareemulator zeigte vor dem Teststart einen SystemUI-ANR-Dialog; der erste Eingabe-Wake-Befehl lief in ein Zeitlimit. Nach Abschluss des Gastsystemstarts wurde der Systemdialog geschlossen und der Testhelfer installiert. v106-emulator-before-tests.png dokumentiert den Zustand vor den Tests; dies ist kein Nachweis der Redmi-Ausfallursache.

## Wiederaufnahme und Testumgebung

Der ursprüngliche vollständige Lauf bestand sechs von acht Tests. Zwei Diensttests warteten vergeblich auf eine Verbindung; Android meldete den Dienst als Binding bei leerer Bound-Liste. Beim erneuten Lauf nach deaktiviertem Ausgangszustand verband sich der Dienst wieder. Ein weiterer Fehler betraf die Suche nach einem App-Knopf, obwohl die geprüfte Pille vollständig innerhalb der Statusleiste liegt. Die Annahme einer veralteten Hilfs-App wurde nach Prüfung aller DEX-Dateien verworfen: Der Knopf war enthalten. Der Berührungsdurchleitungstest prüft jetzt direkt die Systemgeste auf der Oberfläche unter der Pille und behält sämtliche Sperr-/Ausnahmeprüfungen bei.

Der isolierte Kopiertest zeigte anschließend fehlenden Fensterfokus. Eine Aufnahme (`v106-focus-diagnostic.png`) bewies einen über der App liegenden SystemUI-ANR-Dialog des Emulators. Die Zwischenablage darf auf Android 10+ nur die fokussierte Vordergrund-App lesen. Der Test wartet daher explizit auf Fensterfokus und den kopierten Inhalt. Nach Schließen des Gastsystemdialogs und Neustart seiner SystemUI zeigt `v106-focus-recovered-ready.png` einen normalen Launcher. Diese Befunde erklären die Testprobleme, nicht den gemeldeten physischen Redmi-Ausfall. Produktiver Dienst und Sperrlogik wurden während dieser Testkorrekturen nicht geändert.

Die Protokolle `v106-first-connected.log`, `v106-second-connected.log` und `v106-diagnostics-targeted.log` enthalten die früheren Fehlversuche. Maßgeblich für die abschließende Bewertung sind `v106-connected.log` und die aktuellen XML-Berichte.

Der vollständige abschließende Android-Lauf bestand alle acht Tests in 4 Minuten 28 Sekunden nach der Wiederherstellung der Emulator-Systemoberfläche. Der neue Kopiertest bestätigt den vollständigen Bericht einschließlich Dienststatus. Die ausgelieferte App-APK blieb während der Testkorrekturen bytegleich; nur Android-Testcode wurde angepasst.

Die abschließende Aufnahme v106-diagnostics-preview.png zeigt die frisch installierte1.0.6 mit „No local error recorded“, sichtbarem Service-diagnostics-Abschnitt und Kopier-Schaltfläche. Die Ersteinrichtung der Testinstallation war dabei absichtlich nicht aktiviert; der Screenshot belegt die Verfügbarkeit ohne Fehlerdatei, keinen neuen Dienstausfall.
