# NEO PLUS AI

Personal AI agent for Android (Kotlin, Compose, Room, Keystore). Target: Vivo Y35 V2205, Android 14.

## Status
Phase 1 (foundation): dashboard shell, app detection and consent toggles, encrypted memory,
Keystore-backed API key, emergency stop, activity log with verified results, CI.

Phase 4: Agent (natural-language commands planned by the AI into tasks: priority queue, 3 parallel workers, dependencies, approvals, retries, cancel, per-tool verification). Not built yet: caller-facing receptionist (needs a server), full reports.

## Build
Push to GitHub. Actions runs tests and builds the debug APK (artifact: neoplus-ai-debug-apk).
Local: open in Android Studio (JDK 17) and run `app`.
