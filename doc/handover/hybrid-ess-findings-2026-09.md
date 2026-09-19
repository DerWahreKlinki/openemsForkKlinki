# Übergabe: DC-Hybrid-Speicher in OpenEMS – Erkenntnisse aus dem Pytes-Projekt (16.–19.09.2026)

Zielgruppe: die nächste Bearbeiterin/Claude-Instanz, die die **SolarEdge-Komponente**
(`io.openems.edge.solaredge`, `SolarEdgeHybridEssImpl`) an dieselben Erkenntnisse anpasst.
Alles hier wurde am realen Pytes-JS3-Hybrid (Solis-Kern, Modbus TCP) gemessen; die
Commits stehen auf `klinki/reapply` (`7ae47caf90` … `5f59fee90d`). Quellen im Repo:

- `io.openems.edge.pytes/readme.adoc` – Modi, Klemmen, Einspeisebegrenzung, Suche (aktuell)
- `io.openems.edge.pytes/src/io/openems/edge/pytes/ess/{ApplyPowerHandler,AllowedChargeDischargeHandler,PvLimitHandler,PvSurplusProbe}.java` + Tests
- `io.openems.edge.controller.ess.chargedischargelimiter/…/ControllerEssChargeDischargeLimiterImpl.java` + Tests
- Commit-Messages der o. g. Commits (enthalten die Messbelege)

## 1. Die zentrale Erkenntnis: AC-Sollwert vs. Batterie-Sollwert

OpenEMS liefert pro Zyklus **genau einen AC-Sollwert** (Solver löst alle Constraints zu
einer Zahl auf). Es gibt kein „gib alles, was die PV hat“. Bei einem DC-gekoppelten Hybrid
gilt `AC = Batterie + PV`, und es macht einen fundamentalen Unterschied, *was* der WR aus
der Zahl macht:

| | AC-Ausgangsregelung („liefere N W am AC“) | Batterieregelung („Batterie N W“) |
|---|---|---|
| WR-Verhalten | hält AC, verteilt PV/Batterie selbst, **drosselt PV** oberhalb `Sollwert + Batterieaufnahme` per MPPT-Verschiebung (Stringspannung Richtung Leerlauf), **meldet das nicht** | regelt die Batterie, PV läuft am MPP durch, Rest wird exportiert |
| „nicht laden“ (Limiter) | `AC ≥ PV_gemessen` – aber PV_gemessen ist bei Drosselung = Sollwert → **Deadlock** (6 kW verfügbar, 100 W genutzt; Drift-Richtung hängt an ±100 W Batterierauschen) | `Batterie = 0` → alles exportiert, kein Deadlock |
| Wolke | WR hält AC, holt Differenz 5–15 s aus der Batterie, bis der Sollwert nachzieht | AC sinkt sofort mit, Batterie unverändert |
| WR-eigenes Export-Cap aktiv | WR **ignoriert den Sollwert**, lädt Batterie bis zu seinem eigenen Limit, drosselt dann PV | WR **respektiert den Batterie-Sollwert** (0 W bleibt 0 W), drosselt PV |
| Genauigkeit Netzpunkt | ±20 W | ±50–100 W (Bias ~190 W + Verluste ~3 % vorgesteuert, I-Trim) |
| Batterielimits von OpenEMS | WR kennt sie nicht → Klemme im Treiber nötig | direkt durchgesetzt |

**Konsequenz:** Für batteriebezogene Absichten (Limiter, Eigenverbrauch, PV durchlassen)
Batterieregelung verwenden. AC-Regelung nur, wenn der Netzpunkt exakt sein muss (Peak
Shaving). Die Pytes-Config steht seit 18.09. auf `BATTERY_CONTROL`. Für AC-Regelung gibt
es als Workaround die Überschuss-Suche (`PvSurplusProbe`, s. u.) – ein Notbehelf, kein Fix.

**Für SolarEdge prüfen:** In welchem Modus steuert `SolarEdgeHybridEssImpl` (Remote
Control = Batterieleistung → entspricht Batterieregelung)? Wird irgendwo ein AC-Sollwert
in eine PV-Drosselung übersetzt, sodass gemessene PV ≠ verfügbare PV ist (z. B. über den
PV-Leistungs-Sollwert an den Charger)? Wenn ja: `getSurplusPower()` darf in dem Zustand
nichts liefern (ist dort schon so: `PvMode.LIMIT_ACTIVE → null`) und die Freigabe-Logik
muss das berücksichtigen.

## 2. ChargeDischargeLimiter (eigenes Bundle, generisch – gilt für SolarEdge sofort)

Fehler vor `118b7ec63d` (AC-seitige Denke beim DC-Hybrid):
1. `maxSoc`: `AC ≥ 0` blockierte PV-Ladung nicht (Batterie lud bei 90–100 % weiter).
2. Force-Charge `AC ≤ −500` lud um PV zu viel.
3. Richtung aus `ActivePower`: bei PV-Export ist AC > 0 obwohl die Batterie lädt → Taper
   vor `maxSoc` nie erreicht; `APPROACHING_MIN_SOC` tagsüber fälschlich → AC-Deckel →
   erzwungener Netzbezug.
4. Hysterese-Lücke: von `MIN/MAX_SOC_REACHED` über `NORMAL` (10 s ohne Constraint).

Fix: `pv = ActivePower − DcDischargePower` (bei `HybridEss`, sonst 0); alle Grenzen als
Batterieleistung rechnen, Richtung/Taper aus Batterieleistung, Constraint = Batterie + pv;
`fullCharge/DischargePower = Allowed*Power − pv`; Grenzzustände direkt in die Taper-Zone;
Exit aus `APPROACHING_*` erst bei |Batterie| > 100 W (`3e45a98e07`, BMS-Rauschen).
Live verifiziert 19.09.: Taper 2,09 kW → 926 W (83 %) → 215 W (84 %) → 0 (85 %).

**Voraussetzung im ESS:** `DcDischargePower` muss die Batterieleistung ohne großen Lag
sein (Pytes: abgeleitet `ActivePower − PV`, weil der BMS-Wert 6–11 s hinkt) und
`AllowedChargePower`/`AllowedDischargePower` müssen **AC-seitig** gemeldet werden
(`Limit ± PV`, Ladeseite `min(0, limit + PV)`), sonst ist der Taper PV-abhängig.
**Für SolarEdge prüfen:** Semantik von `AllowedChargePower` (DC oder AC?) und Lag von
`DcDischargePower`.

## 3. Dynamische Einspeisebegrenzung (Meta-Limit)

Pytes: `PvLimitHandler` (aus SolarEdge portiert): Export > Limit → AC-Ausgang per Register
auf `Verbrauch + Limit − Toleranz` gedeckelt, Verbrauch = ESS-AC + Netz (5-Zyklen-Mittel),
Schreiben nur bei Änderung/≥ 10 s, Freigabe wenn Deckel > 100 % oder `essAvg < Deckel −
Toleranz` 10 s nach dem letzten Write („Deckel nicht bindend“ = PV eingebrochen). Solange
der Deckel bindet, ist die verfügbare PV unbekannt → Deckel bleibt.

**Bug gefunden 19.09. (`5f59fee90d`):** feste `TOLERANCE_W = 500` (aus der SolarEdge-
70 %-Regel mit kW-Limits). Mit Meta = 400 W wird der Deckel `Verbrauch + 400 − 500 = 0` →
Register 0 % → Haus aus dem Netz, PV aus, **und die Freigabe kann nie greifen**. Fix:
`tolerance = min(500, limit / 2)`, Deckel nie unter dem Verbrauch.

**Für SolarEdge:** `SolarEdgeHybridEssImpl` hat dieselbe feste `tolerance = 500`
(Z. ~365, `pvPowerSetPoint = pvProduction + feedToGrid + feedToGridPowerLimit − tolerance`)
und `HW_TOLERANCE = 500`. Mit kleinem `feedToGridPowerLimit` → PV-Sollwert ≈ 0. Gleiche
Korrektur anwenden (Toleranz ≤ Limit/2, Sollwert nie unter Verbrauch bzw. unter der
aktuellen Batterieaufnahme + Verbrauch). Prüfen, ob die Limit-Freigabe (`PvMode`) bei
kleinem Limit noch erreichbar ist.

Weiterer Befund: Ein WR-eigenes Export-Cap („Backstop“, Pytes-Register 44102/44104) auf
demselben Wert ist immer schneller als die EMS-Begrenzung; die EMS-Begrenzung sieht dann
nie `Export > Limit` und bleibt Reserve. Im Batteriemodus ist das in Ordnung (WR
respektiert den Batterie-Sollwert), im AC-Modus nicht (s. Tabelle). SolarEdge hat m. W.
kein solches WR-Cap – dann ist die EMS-Begrenzung dort die einzige Instanz und muss
robust sein.

## 4. Überschuss-Suche (nur AC-Modus, Pytes `PvSurplusProbe`)

Falls SolarEdge doch einmal in eine „gemessene PV = Sollwert“-Situation kommt: Der Ansatz
ist ein AC-Floor über der gemessenen PV, Schritte 300→600→1200 W alle 10 s solange die PV
folgt (Kriterium: `AC − PV` steigt gegenüber dem Mittel beim Schritt um < 150 W);
Fehlschlag → Rückfall auf das vorher gelieferte Niveau `min(pvAvg, essAvg)` (nie 0),
Pause 60→300 s; Wolke (PV momentan < PV@Schritt − 150) zählt nicht als Decke; Schritt nur
bei eingeschwungenem `gap` (5-Zyklen-Fenster – Transienten nach Neustart sehen sonst wie
Fehlschläge aus); Sättigung = Batterie lädt am Limit **oder** |Batterie| ≤ 150 W (WR lud
bei 99 % trotz BMS-Freigabe nicht: eigenes Max-Charge-SoC); Obergrenze `Verbrauch + Limit
− 300` (unter dem WR-Cap bleiben). 41 Tests in `PvSurplusProbeTest`. Lieber vermeiden,
indem man den Batteriemodus nutzt.

## 5. Kleinere Dinge

- **Kapazität:** Pytes/Solis liefert keine Wh (BMS-Erweiterungsblock 34345 ff. leer,
  43019/43387 Platzhalter). Config-Property `capacity` [Wh] an der Battery, ESS spiegelt
  `Capacity` (`b4b65189e0`). Controller (Limiter `updateUsableSocAndCapacity`) brauchen
  den Wert. Für SolarEdge prüfen, ob `ess/Capacity` gefüllt ist.
- **WR-Einstellungen sind Gesetz:** OpenEMS schreibt keine WR-Parameter (SoC-Grenzen,
  Stromlimits 43117/43118) – Nutzerentscheidung: verwirrend für den User, bei EMS-Crash
  bleibt ein Wert kleben.
- **`DcDischargePower` abgeleitet** (`AC − PV`) enthält die Wandlungsverluste (~3 %) und
  zeigt bei Sollwert 0 20–90 W „Ladung“; echte BMS-Ladung ~30 W (WR-Bias). Nutzer wollte
  keine Verlustkorrektur.
- Idle-Trim: unter |Sollwert| < 50 W integriert der Trim absichtlich nicht (sonst pendelt
  er gegen den WR-Bias).

## 6. Arbeitsweise, die sich bewährt hat

- **Kanäle live lesen** statt Log greppen: `curl -s -u admin:admin
  http://localhost:8084/rest/channel/<comp>/<channel>` (Controller.Api.Rest.ReadOnly).
- Sampler alle 5 s in CSV (PV, Stringspannung/-strom, Netz, AC, DC, SoC, Limit, Zustand)
  – die **Stringspannung** ist der beste Drosselungsindikator (MPP ~470 V vs. 525–545 V).
- Tests ohne Gradle (Eclipse hält den Workspace): javac gegen die `generated/*.jar` der
  Bundles, JUnit 4 per `JUnitCore`, JUnit 5 per Mini-Launcher (Skripte lagen unter
  `~/.claude/projects/…/pytes-tools/`, bei Bedarf neu anlegen: `check_*.sh`, `test_*.sh`,
  Classpath-Datei aus `bnd.bnd`-`-buildpath`).
- Checkstyle einzeln: `java -cp <checkstyle-cp> com.puppycrawl.tools.checkstyle.Main -c
  cnf/checkstyle.xml <files>`; Warnungen mit HEAD-Version vergleichen, nicht absolut.
- Vor jedem Live-Test: welcher Controller hat gerade das Sagen (FixActivePower MANUAL_ON
  war zweimal die „Anomalie“).
- Jede Hypothese am Gerät gegenprüfen – drei Annahmen dieses Projekts („WR lädt vor
  dem Drosseln“, „Cap-Modus ignoriert den Sollwert“, „500 W Toleranz“) galten nur in
  einem Modus oder für große Limits.

## 7. Offen (Pytes)

- Erste echte Drosselung über das EMS-Register 43052 nie gesehen (Backstop kommt zuvor);
  Entscheidung, ob die dynamische Begrenzung im Batteriemodus überhaupt aktiv sein soll.
- Abend-/Nacht-Verhalten und Balancing-Entladung im Batteriemodus über mehrere Tage.
- Upstream-NPE `JsonrpcRoleEndpointGuard` (nur melden).
