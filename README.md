# NEXUS AI — Android

Локальный AI-агент для Android 16. Целевое устройство: POCO F6 (ARM64).

## Реализовано в текущей ветке

- Jetpack Compose launcher UI.
- Выбор GGUF через Android Storage Access Framework.
- Потоковый импорт GGUF в приватное хранилище с проверкой заголовка и свободного места.
- Нативная сборка llama.cpp через CMake/NDK; исходник llama.cpp зафиксирован на commit `ad9d9e8f50e6ff9a6bfbbdd3aef6d74a8428cf4e`.
- JNI bridge для инициализации llama.cpp, загрузки GGUF, локальной генерации токенов, применения GGUF chat template, JSON grammar-constrained decoding и выгрузки модели из RAM.
- `VoiceAliasResolver`: нормализация разговорных названий и падежных форм приложений (например, «телега», «телегу», «телеграмм» → `Telegram`; «ватсап» → `WhatsApp`; «ютуб» → `YouTube`).
- `AppCommandParser`: разбор ограниченных команд открытия приложений на русском языке.
- `InstalledAppLauncher`: поиск установленных приложений по отображаемому имени и запуск через Android launcher intent.
- В интерфейсе добавлена текстовая консоль проверки команд: «Открой телегу», «Запусти ватсап», «Перейди в ютуб».
- В интерфейсе добавлены ручные кнопки загрузки и выгрузки GGUF. Модель не загружается автоматически.
- Сборка ограничена ABI `arm64-v8a`.
- AccessibilityService зарегистрирован; чтение окон по умолчанию не выполняется.
- Типизированная модель действий и fail-closed policy. Ввод и нажатия требуют подтверждения; отправка сообщений, звонки, удаление и покупки заблокированы до появления проверенных исполнителей.

## Пока не реализовано

- Wake-word detector «NEXUS».
- Локальное распознавание речи (ASR) и синтез речи (TTS).
- Подключение текстового парсера и словаря к ASR → разбор намерения → выбор приложения; сейчас парсер можно проверить вручную через поле команды.
- Диспетчер голосовых подтверждений и полноценное выполнение действий.
- Microphone foreground service.

Это промежуточная инженерная сборка, а не готовый автономный голосовой агент. Нативную загрузку модели необходимо проверить на реальном POCO F6; расход RAM зависит от размера, квантования и контекста модели.

## Рекомендуемый стартовый профиль

Для первого теста на POCO F6 используй GGUF 2–4B, квантование Q4_K_M, контекст 2048–4096. Загружай модель вручную и выгружай её после теста, если она больше не нужна.

## Установка обновлений APK — важно

Для обновления поверх установленного NEXUS Android требует одновременно:
- неизменный `applicationId = ai.nexus.mobile`;
- подпись тем же сертификатом, которым подписана установленная версия;
- `versionCode` новой сборки строго выше установленного.

Не меняй `applicationId` и не генерируй новый release keystore для каждого релиза. Храни один постоянный keystore в защищённом месте и передавай его CI только через GitHub Actions Secrets:
`NEXUS_KEYSTORE_BASE64`, `NEXUS_STORE_PASSWORD`, `NEXUS_KEY_ALIAS`, `NEXUS_KEY_PASSWORD`.

Release-сборка теперь должна завершаться ошибкой, если постоянная подпись не настроена. Это предотвращает публикацию APK, который Android не сможет установить как обновление поверх текущей версии. Артефакт `nexus-ai-debug-ci-only` предназначен только для CI-проверок: его подпись не является постоянной и не гарантирует обновление предыдущего APK.

Если на телефоне уже установлена сборка, подписанная другим ключом, Android всё равно отклонит обновление. Потребуется один раз перейти на APK, подписанный тем же ключом, либо удалить старую версию (это может удалить её данные). Само приложение не может обойти проверку подписи Android.

## Сборка

CI использует JDK 17, Android SDK 36, NDK 27.2.12479018, CMake 3.22.1 и Gradle 8.11.1.

Выходной файл: `app/build/outputs/apk/debug/app-debug.apk`.

## Безопасность

LLM не получает доступ к произвольному shell-коду. Любое действие должно проходить через типизированную модель и policy gate. Внешние, финансовые и потенциально необратимые действия должны требовать явного подтверждения пользователя.

## Offline unknown-command agent loop

When the deterministic command parser cannot resolve a request, NEXUS can pass it to the locally loaded GGUF model through llama.cpp. The model's own GGUF chat template is applied when available, and constrained decoding restricts output to the versioned JSON action schema.

The agent uses a bounded observe-plan-act loop (up to ten executed actions per task):
1. Include the current visible accessibility labels as initial context.
2. Ask the local model for one immediate typed action.
3. Validate the JSON response and reject unknown action types or invalid parameters.
4. Execute only allow-listed Android operations.
5. Capture a bounded snapshot of the visible screen and feed the result back to the model.
6. Re-plan after failures or intermediate steps, with duplicate-action detection and a ten-step ceiling.
7. Stop on completion, unsupported requests, invalid output after bounded repair attempts, or a required user confirmation.

Supported model actions are opening an installed app, tapping an exact visible label, entering text into the focused editable field, going back, scrolling, waiting briefly for a screen transition, answering, and reporting completion/unsupported. Taps and text entry require explicit confirmation. Arbitrary shell commands, scripts, and unrestricted intents are never executable model actions. There are no dedicated send/call/delete/purchase tools; any UI sequence that could trigger an external or destructive effect can only proceed through visible, individually confirmed interactions.

Inference runs on-device. No cloud inference endpoint is configured or required. Model loading remains a deliberate user action because GGUF files can consume several gigabytes of memory. Device-side performance and actual model compatibility still require testing on the target phone.


## NEXUS CONTROL — PC dashboard

A separate cross-platform PC dashboard project is available in [`nexus-control/`](nexus-control/README.md). It provides a local Windows 10 / Ubuntu agent, authenticated system telemetry, confirmed power controls, and an Android USB connection using ADB reverse. The GRUB dual-boot menu is supplied as a hardware-specific template and must be adapted to the PC's EFI layout before installation.


## Обновление NEXUS AI из приложения

В настройках главного экрана доступна кнопка **«Обновить NEXUS»**. Она обращается к GitHub Releases этого репозитория, загружает APK последнего стабильного релиза и его файл `.sha256`, проверяет контрольную сумму, package name, versionCode и сертификат подписи относительно установленного приложения. После успешной проверки APK передаётся системному установщику Android. Финальное подтверждение установки выполняется в Android; данные приложения при штатном обновлении сохраняются.

### Публикация релиза

Workflow `.github/workflows/publish-release.yml` запускается при push тега вида `v0.5.0`. Он выполняет unit-тесты, собирает подписанный release APK и публикует APK вместе с SHA-256 в GitHub Releases.

Перед первой публикацией владелец репозитория должен добавить в **Settings → Secrets and variables → Actions** четыре secrets:
- `NEXUS_KEYSTORE_BASE64` — содержимое постоянного keystore в Base64;
- `NEXUS_STORE_PASSWORD`;
- `NEXUS_KEY_ALIAS`;
- `NEXUS_KEY_PASSWORD`.

Используй тот же keystore, которым подписана установленная версия. Не создавай новый ключ для каждого релиза и не загружай keystore или пароли в репозиторий/чат. Если исходного ключа установленной версии нет, обновление поверх неё может быть невозможно из-за проверки подписи Android.

Перед созданием тега обнови `versionCode` (строго больше предыдущего) и `versionName` в `app/build.gradle.kts`, затем создай и отправь тег, например: `git tag v0.5.0 && git push origin v0.5.0`. Тег и versionName должны соответствовать друг другу. Пока secrets не настроены, публикация релиза намеренно не состоится.
