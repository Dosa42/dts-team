# Implementation Plan: TODO.md Completion & Unrestricted Device Backend

Comprehensive plan to complete all tasks outlined in `TODO.md`—including Room persistence, Jetpack Compose setup/error UI, JNI disconnect handling—and build the unrestricted on-device command execution backend for the custom tablet.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> Based on your confirmed choices:
> - **Backend Capabilities**: Full Room persistence (`SavedHost` entity & DAO) + JNI WebSocket disconnect handling + unrestricted on-device command execution engine.
> - **User Interface**: Modern Jetpack Compose with Material 3 styling for the initial Setup screen, loading state, and error handling overlays, seamlessly integrated with the full-screen WebView/kiosk.
> - **WebSocket Execution**: Incoming messages received via WebSocket will be dispatched from native C++ (`client_session`) through JNI to an Android command dispatcher to execute system/device actions directly on the tablet.

- **Confirmed Decision 1: Version Catalog & Room/KSP Integration**: Add KSP, Room (`runtime`, `compiler`, `ktx`), and Jetpack Compose dependencies to `gradle/libs.versions.toml` and configure `app_user/build.gradle.kts`.
- **Confirmed Decision 2: Room Persistence Layer**: Implement `SavedHost.kt` (single row entity `id = 1`), `HostDao.kt`, `LocalDb.kt`, and `HostRepository.kt`.
- **Confirmed Decision 3: JNI WebSocket Disconnect & Message Dispatch**: Expose `nativeDisconnect()` and a native-to-Kotlin message callback in `jni_user.cpp` and `UserCore.kt` to allow resetting connections and executing received commands.
- **Confirmed Decision 4: Unrestricted Device Command Engine**: Create `DeviceCommandExecutor.kt` capable of running shell commands, querying battery/network hardware status, triggering reboot/lock actions, and manipulating tablet settings under the unrestricted permissions granted in `AndroidManifest.xml`.

---

## 1. Overview & Core Concept

- **What It Does**: Upgrades the host application into an autonomous single-app kiosk for a custom Android tablet. On first launch, it presents a Setup screen to specify the host URL (ngrok, HTTPS, or WebSocket). The URL is persistently saved in Room. Subsequent boots automatically load the saved target. If an error occurs, an interactive M3 error panel allows retrying or resetting the host. When connected via WebSocket, incoming payloads trigger direct system commands on the tablet.
- **Target Audience / Environment**: Custom dedicated Android tablet running this app as its exclusive interface with device owner/unrestricted permissions.
- **Key Value**: Resilient boot persistence, zero-touch reconnection, graceful recovery from connection drops, and remote diagnostic/command execution without user intervention.

---

## 2. User Experience & Visual Design

- **State 1: Initial Setup Screen (Compose M3)**:
  - Clean, focused interface with title "Host Setup".
  - Outlined text field for server address with helper text for `ngrok-free.app`, `https://`, `ws://`, or local IP addresses (`192.168.x.x:8085`).
  - Validation: rejects empty/malformed inputs with inline error messaging.
  - Primary button: "Save & Connect" (with loading spinner while persisting and establishing initial handshake).
- **State 2: Active Host Display (Kiosk View)**:
  - Full-screen WebView or active WebSocket session.
  - Floating/Subtle management trigger (accessible via key action, gesture, or top bar dropdown) allowing "Change Host / Reset".
- **State 3: Connection Error Overlay**:
  - Clean Material 3 alert banner/dialog displaying failed URL and error reason.
  - Two prominent action buttons:
    - **Retry**: Re-attempts connection to the saved URL.
    - **Change Host**: Clears the saved host from Room, halts active sessions, and returns to the Setup screen.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Jetpack Compose for Setup/Error vs. Raw HTML in WebView**
  - *Chosen Approach*: Build Setup and Error screens using Jetpack Compose with Material 3, embedding the WebView when in active display state.
  - *Why*: Provides fluid animations, proper keyboard/insets handling, Material 3 density, and immediate reactive state synchronization with Room.
  - *Alternative Considered*: Raw HTML strings in WebView (as previously done in `showMessage`), which lacks tactile UI feedback and cannot cleanly bind to Room reactive streams.

- **Decision 2: Native-to-Kotlin Message Dispatch for Command Execution**
  - *Chosen Approach*: Update `client_session.cpp` and `jni_user.cpp` to notify a registered Kotlin listener when `ix::WebSocketMessageType::Message` arrives.
  - *Why*: Allows incoming remote commands (e.g. JSON actions like `{"action": "shell", "cmd": "..."}`, `{"action": "reboot"}`) to execute safely with Android context access in Kotlin.

- **Decision 3: Room Database Structure (`Single-Row Singleton`)**
  - *Chosen Approach*: A dedicated `saved_host` table with `@PrimaryKey val id: Int = 1`, enforcing exactly one active host address at any time.
  - *Why*: Guarantees atomic updates and eliminates duplicate or stale connection configs.

---

## 4. Technical Architecture & System Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│                        MainActivity (Compose Host)                     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ observes StateFlow
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                          HostViewModel                                 │
├───────────────────────────────────┬────────────────────────────────────┤
│  - uiState: Setup | Connecting    │  - onSaveHost(url)                 │
│             Connected | Error     │  - onResetHost()                   │
└──────────────┬────────────────────┴─────────────────┬──────────────────┘
               │                                      │
               ▼                                      ▼
┌───────────────────────────────┐      ┌─────────────────────────────────┐
│     HostRepository            │      │       UserCore (JNI Bridge)     │
├───────────────────────────────┤      ├─────────────────────────────────┤
│  - getSavedHost(): Flow<Host> │      │  - nativeInit(caPath)           │
│  - saveHost(url)              │      │  - nativeConnect(url)           │
│  - clearHost()                │      │  - nativeDisconnect()           │
└──────────────┬────────────────┘      │  - onMessageReceived(msg)       │
               │                       └──────────────┬──────────────────┘
               ▼                                      │
┌───────────────────────────────┐                     ▼
│     LocalDb (Room SQLite)     │      ┌─────────────────────────────────┐
│   - SavedHost (id = 1, url)   │      │   DeviceCommandExecutor         │
│   - HostDao                   │      ├─────────────────────────────────┤
└───────────────────────────────┘      │  - Shell command runner (`sh`)  │
                                       │  - Hardware / Battery telemetry │
                                       │  - System settings & power ops  │
                                       └─────────────────────────────────┘
```

---

## 5. Step-by-Step Implementation Sequence

1. **Gradle Dependencies (`libs.versions.toml` & `app_user/build.gradle.kts`)**:
   - Add KSP plugin, Room (`room-runtime`, `room-compiler`, `room-ktx`), and Compose M3 dependencies.
   - Configure Room schema export and KSP compiler arguments.
2. **Room Database Layer**:
   - `SavedHost.kt`: Entity with `@PrimaryKey val id: Int = 1`, `val url: String`, and `val updatedAt: Long`.
   - `HostDao.kt`: Flow-based `getHost()`, `upsertHost(host: SavedHost)`, and `deleteHost()`.
   - `LocalDb.kt`: Room database singleton.
   - `HostRepository.kt`: Repository wrapping Room operations.
3. **JNI Disconnect & Message Callback (`jni_user.cpp`, `client_session.h/cpp`, `UserCore.kt`)**:
   - Add `disconnect()` JNI binding `Java_com_example_hostuser_UserCore_nativeDisconnect`.
   - Add message callback mechanism from native `ixwebsocket` to `UserCore.onMessageReceived`.
4. **Unrestricted Backend Command Executor (`DeviceCommandExecutor.kt`)**:
   - Handles remote shell command execution (`sh -c ...`), system inspection, battery telemetry, and tablet actions.
5. **UI & State Machine (`MainActivity.kt` & Compose Screens)**:
   - Setup Screen: URL input, protocol auto-formatting, validation, and save trigger.
   - View Screen: WebView hosting with ngrok headers, back navigation, and menu/gesture for host reset.
   - Error Screen: Detailed failure message with working "Retry" and "Change Host" buttons.
6. **Build & Verification (`compile_applet`)**:
   - Verify complete compilation of Kotlin DSL, KSP annotation processing, C++ JNI, and Android packaging.
