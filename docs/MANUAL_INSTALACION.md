# Manual de instalación y compilación

## Opción A — Compilar en la nube con GitHub Actions (recomendada)

1. Crea un repositorio privado en GitHub (p. ej. `businessshop/space-ai`) y sube el contenido de esta carpeta.
2. En **Actions** se ejecutan solos los workflows **Android** e **iOS** en cada `push`.
3. Al terminar en verde, descarga los artefactos:
   - `spaceai-android` → `apk/debug/app-debug.apk` (instalable de prueba), `bundle/release/app-release.aab`, `apk/release/`, `permisos-apk.txt`, `sha256.txt`.
   - `spaceai-android-device-tests-api29/34` → pruebas en emulador real.
   - `spaceai-ios` → `TestResults.xcresult` (abre en Xcode), logs, y `export/SpaceAI.ipa` si cargaste la firma.

### Secretos para firmar (Settings → Secrets and variables → Actions)

| Secreto | Plataforma | Qué es |
|---|---|---|
| `SPACEAI_KEYSTORE_BASE64` | Android | Tu keystore de subida: `base64 -w0 upload.jks` |
| `SPACEAI_KEYSTORE_PASSWORD`, `SPACEAI_KEY_ALIAS`, `SPACEAI_KEY_PASSWORD` | Android | Datos del keystore |
| `IOS_DIST_CERT_P12_BASE64`, `IOS_DIST_CERT_PASSWORD` | iOS | Certificado *Apple Distribution* exportado como .p12 |
| `IOS_PROVISIONING_PROFILE_BASE64` | iOS | Perfil App Store para `com.businessshop.spaceai` |
| `APPLE_TEAM_ID` | iOS | Team ID de la cuenta de Business Shop A&A |

Sin estos secretos el AAB sale **sin firmar** y no se genera IPA (el workflow lo indica; no se simula).

Crear el keystore de Android (una sola vez; guárdalo fuera del repositorio):

```bash
keytool -genkeypair -v -keystore upload.jks -alias spaceai -keyalg RSA -keysize 4096 -validity 10000
```

Para Google Play usa **Play App Signing**: Google guarda la clave de firma de la app y tú firmas con esta clave de subida.

## Opción B — Android Studio local

1. Android Studio Ladybug o superior, JDK 17.
2. *Open* → carpeta `android/`. Sincroniza Gradle.
3. Ejecutar en teléfono: botón ▶ con el módulo `app` (variante debug, id `com.businessshop.spaceai.debug`).
4. Línea de comandos:

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest      # pruebas JVM
./gradlew :app:assembleDebug                     # APK de prueba
./gradlew :app:connectedDebugAndroidTest         # pruebas en teléfono/emulador conectado
./gradlew :app:bundleRelease                     # AAB (firmado si existe keystore.properties)
```

`android/keystore.properties` (no se versiona):

```
SPACEAI_KEYSTORE_FILE=/ruta/upload.jks
SPACEAI_KEYSTORE_PASSWORD=...
SPACEAI_KEY_ALIAS=spaceai
SPACEAI_KEY_PASSWORD=...
```

Instalar el APK en un teléfono: activa *Instalar apps desconocidas* para tu navegador/gestor de archivos y abre `app-debug.apk`, o `adb install app-debug.apk`.

## Opción C — Xcode local (Mac)

```bash
brew install xcodegen
cd ios
(cd SpaceAICore && swift test)       # pruebas del motor
xcodegen generate
open SpaceAI.xcodeproj
```

En Xcode: target **SpaceAI → Signing & Capabilities → Team** = Business Shop A&A. Ejecuta en tu iPhone (▶). Para TestFlight: *Product → Archive → Distribute App → App Store Connect*.

## Primer uso

1. Abrir SPACE AI → **ANALIZAR MI TELÉFONO** → conceder acceso a fotos (completo o limitado).
2. Opcional: *Configuración → Carpetas y documentos → Agregar carpeta* para incluir documentos.
3. Revisar resultados → **REVISAR Y LIBERAR ESPACIO** → confirmar la lista exacta.
