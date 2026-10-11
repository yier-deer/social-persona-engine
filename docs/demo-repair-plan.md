# QQ Demo Repair Plan

Date: 2026-10-10
Goal: A reproducible local demo using real model inference and NapCat/QQ, retaining React, Spring Boot, and FastAPI.

## Scope And Acceptance

- Project-local Python environment, actual readiness probes, no arbitrary port-based process killing.
- First visit, provider setup, persona creation and owner/bot QQ binding succeed; failed requests visibly fail.
- OneBot identifies its bot from lifecycle/heartbeat; sends use the bound bot; paused/archived personas never invoke inference.
- Successful events execute once, failures stay retryable, sleeping personas wake at their next routine event.
- All inference uses current global model settings. Metadata-only saves preserve keys; persistence errors never report success.
- Custom OpenAI-compatible provider works; Mem0 preserves endpoint paths and handles modern results; memory cannot block the ASGI loop.
- Upstream failures are observable without exposing credentials; health does not claim an untested LLM is connected.
- Builds, isolated regressions, browser checks and local OneBot smoke pass. Real model/NapCat verification requires the user's config location; simulated checks must be labeled.

## Baseline Evidence

- Clean master; work on codex/demo-readiness. Frontend npm ci/build pass.
- ChromaDB0.4.24 + NumPy2.5.3 imports fail with removed np.float_.
- Safe Java mock batch: 66 tests, one obsolete stage-fixture failure. Full tests require configuration isolation.
- Local configuration absent; only system_config.example.json. User's actual config path requested.

## Work Packages

### QQ Routing And Persona State

Files: QQWebSocketHandler/configurator, PersonaService, MessageService and corresponding tests.

- Write failing regressions for top-level self_id, per-bot routing, owner inheritance, reassignment clearing and pause/sleep guards.
- Implement minimal fixes; add listEnabled() for active+sleeping scheduling.
- Restrict upgrade to loopback and make unavailable bound-bot sends observable.
- Run focused Maven tests, serialized with other Java work.

### Scheduler And Configuration

Files: EventTriggerScheduler, EventMapper/XML, SystemConfig/controller, MatchmakerController/tests.

- Write failing regressions for repeated due events, wake path, stale credentials, erased keys, false persistence success and swallowed interview failures.
- Consume successful events once; retry failed events; include sleeping in wake/day generation; use current global settings.
- Preserve existing blank/omitted keys, propagate write failures, isolate all test config paths.
- Correct obsolete interview test stage fixture while retaining production stage validation.
- Run focused then complete isolated Maven tests after transport worker finishes.

### Frontend Flows

Files: LocaleModal, ProviderSelector, OnboardingWizard, PersonaDetail, PersonaEditPanel, CreatePersona and focused tests.

- Reproduce invisible overlay, false save/bind success, stuck loading, image default, stale provider switching and destructive persona edits.
- Unmount overlay, validate responses, restore loading, select valid providers, merge original raw personality data.
- Coordinate hardcoded masterKey removal with backend; preserve UI conventions.
- Run regressions and production build.

### Python And Startup

Files: requirements, LLM providers, memory, API routes/logging, scripts/launchers and tests.

- Test fresh imports, custom provider/defaults/JSON parsing, Mem0 endpoints/results/config changes and route errors before fixing.
- Resolve dependency import, provider/memory contracts, move blocking memory off event loop, sanitize errors/logging.
- Manage only project-owned processes with PID/start-time validation and health probes; retain start.bat/stop.bat entry points.
- Run Python tests, offline API checks, browser/OneBot smoke, then independent read-only review.

## Deliverables And Progress

- Reviewable code and test evidence; docs/demo-guide.md with model configuration, NapCat URL, both QQ identifiers and troubleshooting.
- Verified local admin URL; external checks reported separately if config unavailable.
- [x] Baseline, README, graph, audits and reproductions.
- [ ] Transport/persona state.
- [ ] Scheduler/configuration.
- [ ] Frontend flows.
- [ ] Python/startup.
- [ ] Integration and independent review.
