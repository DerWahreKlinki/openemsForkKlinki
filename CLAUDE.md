# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository

This is `DerWahreKlinki/openemsForkKlinki`, a fork of [OpenEMS](https://github.com/OpenEMS/openems) (`upstream` remote). `develop` is the main/upstream-tracking branch — base PRs on it. `origin/klinki/reapply` carries the fork's own patches on top.

## Project Overview

OpenEMS (FEMS) is a modular Java/OSGi energy management system with three deployable stacks:

- **Edge** (`io.openems.edge.*`) — runs on-site, controls devices, executes energy logic. Targets low-power hardware (~1GB RAM, 2 cores, 4GB disk).
- **Backend** (`io.openems.backend.*`) — aggregates data from many Edge systems, monitors, and supports remote control.
- **UI** (`ui/`) — Angular/Ionic frontend for real-time monitoring, shared by Edge and Backend.

Shared code lives in `io.openems.common*`, `io.openems.core.*`, `io.openems.oem.*`, `io.openems.shared.*`, `io.openems.wrapper`. Every top-level `io.openems.*` directory is an independent OSGi bundle (bnd workspace); there are several hundred of them, one per device driver/controller/service.

Stacks: Java 21, Gradle + bnd workspace (OSGi), JUnit 4/Jupiter, Angular/Ionic + Karma/Jasmine.

## Commands

All Java commands use the Gradle wrapper from the repo root. On Windows PowerShell use `.\gradlew.bat`; `.sh` also works via Bash/WSL.

```powershell
# Single bundle — prefer this over building everything
.\gradlew.bat :io.openems.edge.controller.ess.balancing:test
.\gradlew.bat :io.openems.edge.controller.ess.balancing:checkstyleMain

# Pre-PR checks
.\gradlew.bat checkstyleAll

# Full fat-jar builds (only when app assembly/resolve behavior is affected)
.\gradlew.bat buildEdge          # -> build/openems-edge.jar
.\gradlew.bat buildBackend       # -> build/openems-backend.jar
.\gradlew.bat buildBackendEdge   # -> build/openems-backend-edge.jar
```

Checkstyle config is `cnf/checkstyle.xml`, enforced with zero warnings (`maxWarnings = 0`).

UI commands run from `ui/`:

```powershell
npm test -- --watch=false --browsers=ChromeHeadlessCI   # non-watch test run (preferred)
npm run lint                                             # ng lint + i18n key lint
```

Do not run blocking watch/dev-server commands (`gradlew run`, `npm start`, Karma watch mode) unless explicitly requested.

## Architecture

### OSGi bundle anatomy

Each device/controller/service bundle follows the same shape (e.g. `io.openems.edge.controller.ess.balancing/`):

- `bnd.bnd` — bundle manifest; declares `-buildpath` (compile deps on other bundles) and `-testpath`.
- `src/.../Config.java` — `@ObjectClassDefinition` config annotation.
- `src/.../<Name>.java` — the component's nature interface, declares its `ChannelId` enum.
- `src/.../<Name>Impl.java` — the `@Component`-annotated implementation (`@Activate`, `@Reference`, `@Designate`).
- `readme.adoc` — short description, aggregated into the Antora docs site via the `copyBundleReadmes` Gradle task.
- `test/` — mirrors `src/`, uses `ComponentTest` fixtures.

Good reference bundles: `io.openems.edge.controller.ess.balancing`, `io.openems.edge.evse.chargepoint.keba`, `io.openems.edge.evse.chargepoint.mennekes`.

### Device abstraction (natures)

Device capabilities are expressed as "natures" — interfaces like `ManagedSymmetricEss`, `ElectricityMeter`, `EssDcCharger` (in the `*.api` bundles, e.g. `io.openems.edge.ess.api`, `io.openems.edge.meter.api`). Controllers depend on natures, not concrete device implementations, so control logic is reusable across hardware. **Implementation classes must explicitly list every parent interface of the natures they implement** — OpenEMS's nature detection does not walk the interface hierarchy for you.

### Channels

Channels are the runtime data-exchange mechanism between components (read/write values, e.g. `ActivePower`, `Soc`). Each nature/component declares typed `ChannelId` enums; prefer existing typed `ChannelId` patterns and static imports for channel IDs/constants over ad hoc access.

### Cycle/event execution

Edge logic runs on a cycle, driven by `EdgeEventConstants` topics — not free-running threads. When changing controller behavior, reason about scheduler order (`io.openems.edge.scheduler.*`), channel read/write interactions, and ESS/grid/PV/load energy-flow sign conventions.

### Modbus devices

Devices communicating over Modbus use the shared protocol/register-mapping patterns in `io.openems.edge.bridge.modbus` — follow its conventions rather than hand-rolling protocol parsing in a device bundle.

### Build wiring

`build.gradle.kts` at the root defines cross-cutting Gradle behavior: per-subproject checkstyle/jacoco setup, the `buildEdge`/`buildBackend`/`buildBackendEdge` fat-jar tasks (via `:io.openems.edge.application` / `:io.openems.backend.application` / `:io.openems.backend.edge.application`), and `copyBundleReadmes`/`buildAntoraDocs` for the doc site. `settings.gradle.kts` only explicitly includes the two application projects and `doc`; all other bundles are picked up by the bnd workspace plugin (`biz.aQute.bnd.workspace`).

### UI (`ui/`)

- App code: `src/app/`; shared components/services/pipes/JSON-RPC types: `src/app/shared/`.
- Theme-specific branding, assets, styles, and environment files live under `src/themes/`; keep theme behavior there rather than hardcoding it in generic app code.
- `angular.json` is the source of truth for build/serve configuration and file replacements — check it and the matching `src/themes/*/environments/` file when changing backend/Edge connectivity behavior.
- Update `src/app/shared/i18n/` whenever user-facing text changes.
- Follow `.editorconfig` and `eslint.config.mjs`.

## Testing conventions

- Java: many existing tests use JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`); use JUnit Jupiter only for brand-new test classes where it fits the surrounding bundle. Never mix JUnit 4 and Jupiter imports in one file. Match the framework/style of nearby tests before anything else.
- Use `ComponentTest` patterns and `MyConfig.create().setId("id0").build()`-style config builders, matching nearby tests. Preserve intentional trailing `//` markers in fluent `ComponentTest`/test chains (e.g. `.next(new TestCase() //`) — they're a deliberate style, not stray formatting.
- Controller tests should check: scheduler/order side effects, ESS/grid/PV/load sign conventions, channel values incl. invalid/optional states, min/max power edge cases, and config activation/update behavior.
- Full Java test-writing guidance: `.github/skills/oe-junit/SKILL.md`.
- UI: Karma + Jasmine, run from `ui/`. Shared test helpers live in `ui/src/app/shared/components/shared/testing/` (`TestContext`/`TestingUtils` in `utils.spec.ts`, `OeFormlyViewTester`/`OeChartTester` in `tester.ts`). Use `DummyConfig` from `src/app/shared/components/edge/edgeconfig.spec.ts` for `EdgeConfig`/`Edge` test data instead of constructing them manually. Full guidance: `.github/skills/oe-ui-test/SKILL.md`.

## Working style

- Prefer small, focused diffs; avoid unrelated reformatting.
- Preserve existing behavior unless a change is explicitly requested.
- Follow nearby code and existing FEMS/OpenEMS utilities/modules/config patterns before introducing new abstractions. Add a new bundle only when explicitly required, following the closest existing bundle's structure.
- Comments only when they explain non-obvious intent, constraints, or tradeoffs — in English.
- Do not bump dependencies, wrappers, or plugins, and do not introduce new frameworks, unless explicitly requested.
- Run the narrowest relevant validation for the changed area (single Gradle bundle task, or a single UI spec/lint target) rather than the full suite.
