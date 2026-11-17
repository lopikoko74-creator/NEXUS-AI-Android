# NEXUS AI

Локальный голосовой Android-агент для POCO F6 / Android 16.

## Статус разработки

**Early development — не готовый автономный ассистент.** Цель проекта: офлайн-модель, голосовая активация «NEXUS», распознавание речи, озвучивание ответов и управление Android через разрешённые системные API.

### Целевая архитектура

1. Wake-word engine — локальное обнаружение «NEXUS».
2. ASR — локальное распознавание русской речи.
3. LLM — GGUF-модель через llama.cpp.
4. Planner — формирование структурированного плана инструментов.
5. Policy/confirmation layer — проверка разрешений и голосовое подтверждение рискованных действий.
6. Android action bridge — AccessibilityService и официальные Android API.
7. TTS — голосовые ответы.

## Устройство разработки

POCO F6, Snapdragon 8s Gen 3, 8 ГБ физической RAM + виртуальное расширение. Виртуальная RAM не считается эквивалентом физической. Начальная цель — компактная instruct-модель 3B–4B в GGUF Q4, контекст 2K–4K; фактический выбор после измерений на устройстве.

## Принципы безопасности

- Модель не получает произвольный доступ к shell или исполнению кода.
- Действия проходят через типизированный allow-list инструментов.
- Отправка сообщений, удаление данных, платежи, публикации и другие внешние/необратимые действия требуют голосового подтверждения.
- Приложение показывает постоянное системное уведомление во время работы микрофонного сервиса.
- Первичные разрешения Android выдаются пользователем вручную.

## Сборка

Открыть проект в Android Studio с JDK 17 и Android SDK 36. Gradle wrapper будет добавлен в ближайшем коммите.

## Roadmap

- [ ] Базовый Android UI и foreground service
- [ ] llama.cpp Android/JNI integration
- [ ] GGUF model loading
- [ ] Russian ASR
- [ ] Wake word «NEXUS»
- [ ] Russian TTS
- [ ] Typed tool planner and executor
- [ ] Voice confirmation flow
- [ ] POCO F6 / HyperOS battery and background tests
- [ ] CI build and APK release
