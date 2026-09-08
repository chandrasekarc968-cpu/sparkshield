# Modern SOC UI for SparkShield

This plan outlines the transformation of SparkShield from a single-screen diagnostic app to a multi-tab professional SOC (Security Operations Center) dashboard.

## User Review Required

> [!IMPORTANT]
> This change introduces a `BottomNavigationView` and `Fragment`-based architecture, replacing the current monolithic `MainActivity`. All existing telemetry processing and service logic will be preserved and accessed via the `MonitoringViewModel`.

## Proposed Changes

### Build & Dependencies

#### [MODIFY] [libs.versions.toml](file:///C:/Users/LENOVO/AndroidStudioProjects/spark/android_app/gradle/libs.versions.toml)
- Add Navigation component versions and libraries.
- Add KSP plugin for Room.

#### [MODIFY] [build.gradle.kts (App)](file:///C:/Users/LENOVO/AndroidStudioProjects/spark/android_app/app/build.gradle.kts)
- Apply KSP plugin.
- Add Room compiler and Navigation dependencies.

---

### Navigation Resources

#### [NEW] [bottom_nav_menu.xml](file:///C:/Users/LENOVO/AndroidStudioProjects/spark/android_app/app/src/main/res/menu/bottom_nav_menu.xml)
- Define 5 main tabs: Dashboard, Monitor, Detection, History, Settings.

#### [NEW] [nav_graph.xml](file:///C:/Users/LENOVO/AndroidStudioProjects/spark/android_app/app/src/main/res/navigation/nav_graph.xml)
- Configure fragment destinations.

---

### UI Components (Fragments & Layouts)

#### [NEW] Dashboard Component
- `DashboardFragment.kt` and `fragment_dashboard.xml`.
- Displays high-level protection status, risk level, and connected meter info.

#### [NEW] Monitor Component
- `MonitorFragment.kt` and `fragment_monitor.xml`.
- Displays live sensor metrics (Peak, Rise, Decay, Optical).

#### [NEW] Detection Component
- `DetectionFragment.kt` and `fragment_detection.xml`.
- Displays real-time AI classification, confidence gauges, and engine stats.

#### [NEW] History Component
- `HistoryFragment.kt`, `fragment_history.xml`, and `HistoryAdapter.kt`.
- Lists persisted tamper events from Room with filtering.

#### [NEW] Settings Component
- `SettingsFragment.kt` and `fragment_settings.xml`.
- Configuration for BLE/Mock mode and engine details.

---

### Core UI Refactoring

#### [MODIFY] [activity_main.xml](file:///C:/Users/LENOVO/AndroidStudioProjects/spark/android_app/app/src/main/res/layout/activity_main.xml)
- Replace diagnostic UI with `NavHostFragment` and `BottomNavigationView`.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/LENOVO/AndroidStudioProjects/spark/android_app/app/src/main/java/com/sparkshield/android/MainActivity.kt)
- Initialize `NavController` and link with `BottomNavigationView`.

#### [MODIFY] [MonitoringViewModel.kt](file:///C:/Users/LENOVO/AndroidStudioProjects/spark/android_app/app/src/main/java/com/sparkshield/android/ui/MonitoringViewModel.kt)
- Expose history flows and filtering logic.

## Verification Plan

### Automated Tests
- Build verification to ensure all new dependencies and resources are correctly linked.
- Unit tests for `MonitoringViewModel` history filtering logic.

### Manual Verification
- Verify navigation between all 5 tabs.
- Ensure telemetry data continues to flow to all fragments via the shared ViewModel.
- Test "Inject Tamper" functionality (moving to Settings or Dashboard).
- Verify dark theme consistency across all screens.
