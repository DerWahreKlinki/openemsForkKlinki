# SolarEdge: Einspeisebegrenzung vs. Batterie – Messungen und Stand (19.09.2026)

Fortsetzung von `hybrid-ess-findings-2026-09.md` (Pytes) für die SolarEdge-Komponente
(`io.openems.edge.solaredge`, `SolarEdgeHybridEssImpl` / `SolaredgeDcChargerImpl`).
Alles hier wurde am realen SolarEdge-Hybrid (192.168.3.38:1502, Unit 14, 10 kW, LG-Batterie
8,7 kWh) mit einem lokalen Edge aus Eclipse gemessen, während `ess0` im Live-System
blockiert war. Messreihen und Log: `D:\Nextcloud\Sync\Privat\ClaudeMemory\openems-tools\`
(`solaredge_samples_2026-09-19_*.csv`, `openems_2026-09-19_test.log`).

## 1. Ausgangsproblem

Wenn die Einspeisebegrenzung greift, lädt der Wechselrichter die Batterie, obwohl OpenEMS
(ChargeDischargeLimiter: `maxSoc`, Preislimit) nicht laden will. Am Pytes ist das durch den
Wechsel auf Batterieregelung erledigt; SolarEdge bietet diesen Weg nicht.

## 2. Wie SolarEdge gesteuert wird (Stand Code)

| Hebel | Register | Seite | Code |
|---|---|---|---|
| Batterie-Sollwert | 0xE00D Command Mode, 0xE00E ChargeLimit, 0xE010 DischargeLimit | DC/Batterie | `applyChargePower()` → Mode 3 (laden), 4 (entladen), Idle = Mode 3 mit 0/0 W |
| „PV-Limit“ | 0xF001 Active Power Limit [%] | **AC-Ausgang** (PV + Batterie) | `limitPvPower()` → Charger schreibt Prozent + Commit |

Es gibt **kein DC-/PV-seitiges Limit-Register**: PCOP (Power Control Open Protocol) kennt nur
AC-Limits (F001, F30C, F322, RRCR); die Technote listet SunSpec 101/102/103 (+160 nur lesend)
und Zähler, kein Speichermodell 124; der Batterie-Block E1xx ist read-only.

## 3. Messergebnisse

Rahmen: SoC 91–98 %, PV 4,7–7,7 kW, andere Erzeuger am Netzpunkt (Victron ess1, Fronius)
mit ~0,5–1,6 kW Export, `setPointMode = AC_SETPOINT`, Limiter `ABOVE_MAX_SOC` (maxSoc 90).

| Zeit | Limit | Cap (0xF001) | WR-AC | Export | PV | Batterie | Sollwert/Mode |
|---|---|---|---|---|---|---|---|
| 15:50 | 2000 | 0–2 % (Startartefakt, s. 4) | 0 W | −1,5 kW | 5,0 kW | **−5000 W** (Laden, WR-Maximum, SoC 98 %) | Idle: Mode 3, ChargeLimit 0 |
| 16:26–16:28 | 6000 | 57–68 % ≈ 6,3 kW | 5,7–5,9 kW | −4,9 kW | 5,5–6,0 kW | 0 W (Entladung 2,9 kW → 0) | FixActivePower Entladen 3 kW, Mode 4/3 |
| 16:32–16:33 | 2000 | 23 % ≈ 2,3 kW | 2,3 kW | −1,5 kW | 5,8 kW, **ungedrosselt** | **−3,4 bis −3,6 kW** | Entladen 3 kW angefordert, Mode 3 |
| 16:39–16:41 | 1000 | 13–18 % ≈ 1,3–1,6 kW | 1,3–1,6 kW | −0,5 bis −0,7 kW | 4,7–5,9 kW, ungedrosselt | **−3,2 bis −4,4 kW** | Entladen 3 kW angefordert, Mode 3 |

Befunde:

1. **Der AC-Cap wird exakt eingehalten**, der Export bleibt unter dem Limit (bei 1/2/6 kW).
2. **Unter Cap drosselt der WR die PV nicht**, sondern lädt die komplette Differenz
   PV − Cap in die Batterie – bei `ChargeLimit 0` (Idle) und sogar gegen einen expliziten
   Entladebefehl. Der Batterie-Sollwert ist unter Cap für den WR bedeutungslos; die Batterie
   ist sein Puffer, gedrosselt wird erst, wenn sie voll ist. Die Ladeleistung ist dabei das
   WR-Maximum (`MaxChargeContinuesPower` 5 kW), nicht das konfigurierte `chargePowerLimit`.
   Dritte berichten dasselbe (solaredge-modbus-multi, Discussion #469: „Active Power Limit 0
   → AC zero, battery keeps DC charging from solar“).
3. **Unter Cap entlädt der WR nicht** (Entladung 2,9 kW → 0 in einem Zyklus). Das ESS meldet
   in dem Zustand `AllowedDischargePower = PV` (bereits so im Code, Z. ~1093).
4. **Der WR bleibt ~500 W (~8 %) unter dem gegebenen Cap** (Cap 6,3 kW → Ausgang 5,7–5,9 kW).
5. **Ohne EMS-Kommandos** (Command-Timeout 60 s) fällt der WR in den Default-Modus 7 und lädt
   den PV-Überschuss selbst (beobachtet beim Wechsel Live-Edge → lokaler Edge).
6. Die Stringspannung ändert sich beim Cap nicht (~761 V); die Leistung wird über den
   DC-Strom geführt (Optimizer-System), Stringspannung ist hier **kein** Drosselungsindikator.

## 4. Codeänderungen (Branch `klinki/solaredge-pvlimit`, nicht gemergt)

Rückfallpunkt: Tag `pre-solaredge-pvlimit` = `3eb9b5004` (Handover) auf `klinki/reapply`.

- `db1ec03ea` – `PvLimitHandler` (aus Pytes zurückportiert) statt der Formel
  `pvProduction + feedToGrid + limit − 500`: Cap = Verbrauch (ESS-AC + Netz) + Limit − Toleranz,
  Toleranz `min(500, Limit/2)`, nie unter Verbrauch, Schreiben nur bei Änderung/≥ 10 s,
  kein Rückkopplungsterm über die gedrosselte PV mehr. Charger: `_setAcOutputLimitPercent(int)`
  (schreibt genau den Prozentwert, `PvMode` konsistent). Neue Kanäle `PvLimitActive`,
  `AcOutputLimit`. Neue Config-Property **`idleMode`**: `PV_AC_ZERO` (Default, heutiges
  Verhalten) oder `OFF` (Command Mode 0).
- `77be939a9` – Warm-up: keine Begrenzung, bevor die Mittelwert-Fenster voll sind.
  `ess0/ActivePower` läuft durch einen 5-Zyklen-Filter und ist beim Start 0, der Zähler ist
  sofort gültig → „Verbrauch“ −5,5 kW → 0 %-Cap (15:49:48 live passiert).
- `d940a4a52` – Freigabe-Hysterese: „Cap nicht bindend“ erst bei Ausgang < Cap − max(Toleranz,
  20 % des Caps), wegen Befund 4 (sonst Cap/Freigabe im 20-s-Takt, live bei 6 kW gesehen).
- Tests: `PvLimitHandlerTest` (17), `testApplyChargePowerIdleModeOff`. 13 Tests des Bundles
  waren schon vorher rot (`expected WriteValue [2000] got [0]`, 5-Zyklen-Mittelung in
  `adjustChargePowerBasedOnAverage`) – nicht angefasst. Checkstyle: keine neuen Warnungen.

## 5. Nebenbefunde

- **`DC_SETPOINT` passt nicht zum neuen Limiter.** Seit `118b7ec63` setzt der Limiter
  AC-Constraints (`AC ≥ Batterie + PV`). `SolarEdgeHybridEssImpl` interpretiert den Sollwert
  bei `DC_SETPOINT` (Config-Default, Live-Config hat keinen Eintrag) als Batterieleistung →
  aus „nicht laden“ wird Mode 4 mit `DischargeLimit = PV`. Für den Test lief `AC_SETPOINT`;
  Kette Limiter → Solver → ESS war damit stimmig (`AC ≥ pv` → Batterie 0 → Idle).
  **Vor einem Live-Einsatz des Limiters mit SolarEdge klären** (Config auf `AC_SETPOINT`
  oder Limiter für DC-Sollwert-ESS anpassen).
- `pvLimitActive → AllowedDischargePower = 0` plus Limiter `AC ≥ pv` ist bei `DC_SETPOINT`
  unlösbar → Solver-Nulllösung → Idle (bei `AC_SETPOINT` ist `AllowedDischargePower = pv`,
  lösbar).
- Config-Update an `ess0` (Re-Aktivierung) wirft den Limiter ~15 s in `Error → Undefined`;
  in der Zeit hat Balancing bei 98 % mit bis zu 3,9 kW geladen.
- `AC_SETPOINT`-Regelkreis ist unruhig: `ChargePowerWanted` pendelt jeden Zyklus ±1 kW,
  Mode wechselt 3/4/3/4 (PID-Rampe + 5-Zyklen-Filter + WR-Verzögerung). Nicht gefährlich,
  gesondert anschauen.
- Der Verbrauch `ESS + Netz` ist bei mehreren Erzeugern der Netto-Verbrauch; ein Standortlimit
  von 1–2 kW lässt dem SolarEdge fast nichts (Formel ist für ein Standortlimit korrekt).
- `PvSurplusProbe`-artige Ansätze sind bei SolarEdge unnötig: PV wird unter Cap gar nicht
  gedrosselt, solange die Batterie Platz hat; `getSurplusPower()` liefert unter `LIMIT_ACTIVE`
  weiterhin `null`.

## 6. Offen / nächste Schritte

1. **Command Mode 0 „Off“ unter Cap testen** (`idleMode = OFF`, 5 min) – der einzige Modus,
   in dem die Batterie für den WR kein Ziel ist. Braucht Ladeverbot bei SoC deutlich < 100
   (sonst nicht von „Batterie voll“ unterscheidbar) und dabei prüfen, ob die Batterie aus
   „Off“ wieder anspringt (Entladebefehl per FixActivePower).
2. Zweiter Kandidat: Storage Control Mode 0 „Disabled“ (0xE004).
3. SolarEdge *Export Limitation Application Note v3.0 (Aug 2026)* lesen (Knowledge Center
   blockt automatische Abrufe) – gibt es inzwischen eine Priorität „PV drosseln vor Batterie
   laden“? WR-Firmware-Stand prüfen.
4. Wenn nichts davon greift: Einspeiselimit und Ladeverbot sind bei SolarEdge nicht
   gleichzeitig durchsetzbar → im Limiter/ESS entscheiden, was im Konflikt gewinnt.

## 7. Lokaler Testaufbau (für die Wiederholung)

- Eclipse startet `EdgeApp.bndrun`, Config `C:\openems\config` (ausgedünnt: `modbusWR`,
  `ess0`/`charger0`/`meter0`, `chargeDischargeLimiter0`, `ctrlBalancing0`,
  `ctrlFixActivePower0` – beide auf `ess0` –, REST RW :8084, Websocket :8075; Backend, ToU,
  GridOptimizedCharge, Limiter1 aus; Influx Bucket `test`). `ess0`: `AC_SETPOINT`,
  `feedToGridPowerLimit = -1`, `debugMode`; Admin-Passwort lokal auf `admin` zurückgesetzt
  (`Core/User.config`, Original in `User.config.bak`).
- **Live-System vorher blockieren** – zwei EMS am WR schreiben gegeneinander; 60 s nach dem
  letzten Kommando fällt der WR in Modus 7.
- Log: File-Appender direkt in `config/org/ops4j/pax/logging.config`
  (`c:/openems/log/openems.log`); der `Core.Logger`-Pfad funktioniert auf Windows nicht
  (Pfad wird als URL geparst, auch `file:///` schlägt fehl).
- Config-Änderungen zur Laufzeit per JSON-RPC `updateComponentConfig` (REST :8084, `id` muss
  eine UUID sein); Dateien werden zur Laufzeit nicht neu gelesen.
- Skripte: `sample_solaredge.py` (Sampler, ≥ 15 s Intervall – dichteres Polling trifft das
  Jetty-AcceptLimit und blockiert die UI), `discharge_guard.py` (Entladung mit
  Export-Wächter; Meta `maximumGridFeedInLimit` 9500 in FixActivePower reagiert zu träge,
  Überschwinger bis 9,65 kW gesehen).
- Nach Code-Änderungen: F5 auf `io.openems.edge.solaredge` in Eclipse, bnd tauscht das
  Bundle im laufenden Edge aus.
