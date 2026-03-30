# Semetrics Android SDK

Kotlin SDK для отправки аналитических событий на платформу Semetrics.

**Требования:** Android minSdk 24+, Kotlin 1.9+

## Установка

`settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`build.gradle.kts` (app):
```kotlin
dependencies {
    implementation("com.github.trombocit.semetrics-android:semetrics:VERSION")
}
```

## Быстрый старт

```kotlin
// MyApplication.kt
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Semetrics.configure(
            context = applicationContext,
            apiKey = "sm_live_ваш_ключ",
            endpoint = "https://semetrics.ru/events",
        )
    }
}
```

```kotlin
// В любом Activity/Fragment/ViewModel
Semetrics.track(
    eventName = "button_clicked",
    userId = "user_123",
    properties = mapOf("button" to "checkout", "screen" to "cart"),
)
```

## Параметры configure()

| Параметр | По умолчанию | Описание |
|----------|-------------|----------|
| `context` | обязательный | ApplicationContext |
| `apiKey` | обязательный | API-ключ проекта |
| `endpoint` | `https://semetrics.ru/events` | URL сервиса |
| `syncIntervalMinutes` | `15` | Интервал периодической синхронизации |

## Гарантия доставки

- События сохраняются в **Room** (SQLite) до успешной отправки
- **WorkManager** гарантирует запуск при наличии сети, даже после перезапуска устройства
- Retry: exponential backoff через WorkManager (до 3 попыток, потом батч дропается)
- Periodic sync каждые 15 минут (минимальный интервал WorkManager)

## Принудительный flush

```kotlin
Semetrics.flush()  // OneTimeWorkRequest, отправляется при первой возможности
```
