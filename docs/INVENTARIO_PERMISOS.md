# Inventario de permisos

## Android (`android/app/src/main/AndroidManifest.xml`)

| Permiso | Versiones | Motivo | Si se niega |
|---|---|---|---|
| `READ_MEDIA_IMAGES` | 13+ | Analizar fotos | No se analizan fotos |
| `READ_MEDIA_VIDEO` | 13+ | Analizar videos | No se analizan videos |
| `READ_MEDIA_VISUAL_USER_SELECTED` | 14+ | Acceso parcial (solo fotos elegidas) | Se reporta "Limitado" |
| `READ_EXTERNAL_STORAGE` (maxSdk 32) | 10–12 | Leer fotos y videos | No se analizan |

**Eliminados explícitamente** del manifiesto final (`tools:node="remove"`): `INTERNET`, `ACCESS_NETWORK_STATE`. El workflow de CI falla si el APK contiene `INTERNET`, `MANAGE_EXTERNAL_STORAGE` o `QUERY_ALL_PACKAGES`.

**No se solicitan:** `MANAGE_EXTERNAL_STORAGE` (Google Play no lo permite para limpiadores), `QUERY_ALL_PACKAGES`, ubicación, contactos, cámara, notificaciones.

**Accesos sin permiso de manifiesto:**
- Carpetas de documentos: *Storage Access Framework* (`ACTION_OPEN_DOCUMENT_TREE`); el usuario elige cada carpeta y puede revocarla.
- Borrar fotos/videos de otras apps: diálogo del sistema (`MediaStore.createTrashRequest` / `createDeleteRequest`).
- Espacio del volumen: `StorageStatsManager` (sin permiso).

**Respaldo:** `allowBackup=false` y reglas de extracción que excluyen todo.

## iOS (`ios/project.yml` → Info.plist)

| Clave | Motivo | Texto mostrado (es-MX) |
|---|---|---|
| `NSPhotoLibraryUsageDescription` | Leer y eliminar fotos/videos (lectura y escritura) | "SPACE AI analiza tus fotos y videos en este iPhone para encontrar duplicados, fotos similares y videos grandes. No se sube nada." |
| `NSPhotoLibraryAddUsageDescription` | Guardar el video comprimido | "SPACE AI guarda copias comprimidas de tus videos en tu fototeca solo cuando tú lo confirmas." |
| `PHPhotoLibraryPreventAutomaticLimitedAccessAlert` | Evitar alerta repetida en acceso limitado | — |
| `ITSAppUsesNonExemptEncryption = false` | Solo cifrado estándar del sistema (exento) | — |

**Manifiesto de privacidad** (`PrivacyInfo.xcprivacy`): sin rastreo ni recopilación. APIs de motivo requerido: espacio en disco `85F4.1`, `E174.1`; marcas de tiempo de archivo `3B52.1`, `C617.1`; UserDefaults `CA92.1`.

**Documentos:** selector de Archivos (`fileImporter` con carpetas); marcadores con ámbito de seguridad.

## Formularios de las tiendas
- **Google Play — Seguridad de los datos:** "No se recopilan datos" · "No se comparten datos".
- **App Store — Privacidad de la app:** "Datos no recopilados".
