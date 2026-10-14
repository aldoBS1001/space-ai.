# Documentación técnica

## 1. Arquitectura

Dos apps nativas que comparten **el mismo motor funcional**, implementado dos veces con algoritmos idénticos y las mismas pruebas:

```
           ┌──────────── Plataforma (adaptadores) ────────────┐
 Android:  MediaStore · SAF · StorageStatsManager · Media3 · Keystore · Compose
 iOS:      PhotoKit · Files (bookmarks) · URLResourceValues · AVFoundation · CryptoKit · Vision · SwiftUI
           └──────────────────────┬────────────────────────────┘
                                  │ MediaItem, GrayImage, StorageSnapshot
           ┌──────────────── Motor central (sin UI ni SO) ─────────────────┐
 Kotlin:   android/core          Swift: ios/SpaceAICore
           ExactDuplicateFinder · PerceptualHash · QualityAnalyzer · SimilarityGrouper
           ScanAnalyzer · RecommendationEngine · VideoCompressionPlanner/Validator
           CleanupPlan · SafeCleanupEngine · HashCache · ByteFormat
           └────────────────────────────────────────────────────────────────┘
```

El motor no conoce la plataforma: recibe `MediaItem` y lectores de contenido (`ContentSource`), y devuelve categorías, recomendaciones y reportes. Así la lógica sensible (qué se propone borrar y cuándo se permite) se prueba en JVM/Swift puro.

## 2. Módulos solicitados → implementación

| # | Módulo | Android | iOS |
|---|---|---|---|
| 1 | Análisis de almacenamiento | `AndroidStorageMeter` (StorageStatsManager), `MediaStoreScanner`, `ScanOrchestrator` | `IOSStorageMeter`, `PhotoLibrary.fetchAll`, `ScanService` |
| 2 | Detección de duplicados | `core/ExactDuplicateFinder` | `SpaceAICore/ExactDuplicates.swift` |
| 3 | Clasificación inteligente | `core/ImageAnalysis`, `SimilarityGrouper`, `ScanAnalyzer` | Igual + `VisionRefiner` (VNFeaturePrint) |
| 4 | Compresión de videos | `data/VideoCompressor` (Media3 Transformer) | `VideoCompressorIOS` (AVAssetExportSession) |
| 5 | Gestión de archivos | `DocumentTreeScanner` (SAF) | `AuthorizedFolders` (bookmarks con ámbito de seguridad) |
| 6 | Limpieza segura | `core/SafeCleanup` + `AndroidCleanupExecutor` | `SafeCleanup.swift` + `IOSCleanupExecutor` |
| 7 | Historial y métricas | `HistoryStore` (AES-GCM, Android Keystore) | `HistoryStore` (AES-GCM, Llavero) |
| 8 | Configuración y privacidad | `AppSettings`, `SettingsScreen` | `@AppStorage`, `SettingsView` |

## 3. Algoritmos

### 3.1 Duplicados exactos (riesgo bajo)
1. Agrupar por tamaño exacto en bytes (sin leer nada).
2. SHA-256 de los primeros 64 KB solo en tamaños repetidos.
3. SHA-256 completo en streaming (bloques de 64 KB, nunca el archivo entero en memoria) solo donde coincide el parcial.
Solo la etapa 3 confirma. Los ilegibles (corruptos, solo en iCloud) se reportan aparte y **nunca** se asumen duplicados. Se conserva una copia por grupo: favorita > más antigua > nombre más corto.

Medios y documentos se comparan **por separado**: un mismo archivo físico puede verse por MediaStore y por una carpeta SAF; compararlos lo marcaría como "su propio duplicado" y borrar uno borraría el original.

### 3.2 Fotos similares (riesgo medio)
- **dHash 64 bits** sobre miniatura del sistema (512 px) reducida por promedio de área a 9×8.
- Casi-idénticas (≤ 2 bits) en toda la biblioteca mediante **BK-tree**.
- Similares (≤ 4/8/12 bits según sensibilidad Estricta/Normal/Amplia) **solo dentro de 10 minutos**: dos cielos azules de años distintos no son la misma foto.
- Guarda de relación de aspecto (±8 %). Capturas de pantalla excluidas.
- Ráfagas del sistema (`burstIdentifier` en iOS) se agrupan aunque haya movimiento.
- iOS añade una segunda opinión con **Vision `VNGenerateImageFeaturePrintRequest`** que solo puede quitar miembros, nunca añadir.
- Rendimiento medido: 10 000 firmas agrupadas en ~170 ms (JVM).

### 3.3 Calidad
- **Nitidez**: varianza del Laplaciano 4-vecinos a 512 px (Welford, estable). Borrosa si < 60 (conservador: 30).
- **Oscura**: luma media < 45 y > 70 % de píxeles con luma < 40.
- **Baja calidad**: < 0.3 MP o > 60 % sobreexpuesta.
- **Mejor foto del grupo**: favorita > puntuación (0.6·nitidez + 0.25·exposición + 0.15·recorte) > resolución.
Favoritas y la mejor de cada grupo nunca se sugieren por calidad.

### 3.4 Videos
- Estimación: resolución destino (lado corto 1080/720/540), bitrate = píxeles × fps × bpp (0.075/0.070/0.060), nunca mayor que el bitrate de la fuente, +2 % contenedor. Solo se ofrece si ahorra ≥ 20 % y ≥ 10 MB.
- Requiere espacio libre ≥ salida estimada × 1.1 + 200 MB.
- Validación de la copia **medida**: decodifica primer y último cuadro, duración ±max(1 s, 2 %), tamaño menor que el original. Si falla, la copia se borra y el original no se toca.
- El usuario debe **reproducir** la copia antes de poder conservarla; luego se guarda en la galería y solo entonces se ofrece eliminar el original (riesgo medio, con aprobación).

### 3.5 Riesgo y recomendaciones
| Riesgo | Categorías | Regla |
|---|---|---|
| Bajo | Duplicados exactos | Preseleccionados; se pueden ejecutar sin casilla extra |
| Medio | Similares, ráfagas, capturas, borrosas, oscuras, baja calidad, grabaciones de pantalla, original tras compresión | Requiere casilla "Revisé estos N archivos" |
| Alto | Videos/archivos grandes, antiguos | Nunca preseleccionados; requiere casilla |

`potentiallyRecoverableBytes` es la **unión sin duplicar** de lo sugerido + ahorro estimado de compresión + temporales propios; se presenta siempre como estimación.

## 4. Eliminación segura (6 pasos)

`CleanupPlan.build` (pasos 1–2) rechaza: elementos marcados para conservar, no eliminables, y planes que borrarían **todas** las copias de un grupo. `SafeCleanupEngine.run`:
3. Exige `CleanupApproval` ligada al `plan.id` y que cubra el riesgo máximo.
4. Ejecuta con APIs oficiales (Android: `createTrashRequest`/`createDeleteRequest` o `RecoverableSecurityException` en Android 10; iOS: `PHAssetChangeRequest.deleteAssets`; archivos: `DocumentsContract.deleteDocument` / `FileManager`).
5. Verifica cada elemento consultando de nuevo al sistema y registra en historial cifrado.
6. Mide el volumen antes y después. Estados: `VERIFIED_FREED` (aumento medido ≥ 50 % de lo borrado), `PENDING_SYSTEM_TRASH`, `UNVERIFIED`, `PARTIAL_FAILURE`, `NOTHING_DELETED`, `CANCELLED`. **Nunca** se dice "liberado" sin medición.

## 5. Robustez
- Cancelación cooperativa en todo el análisis y la limpieza.
- Caché de hashes con escritura atómica (temporal + rename) cada 200 entradas y al terminar/cancelar: un cierre inesperado no pierde el avance ni corrompe la caché; una caché ilegible se descarta.
- Concurrencia limitada (3 hilos) para gama media.
- Sin red: Android elimina `INTERNET` del manifiesto final; iOS no hace llamadas de red (PhotoKit con `isNetworkAccessAllowed = false` en análisis).

## 6. Textos e idiomas
Una sola tabla (`tools/gen_android_strings.py`) genera `values` (en) y `values-es` (es-MX); `tools/gen_ios_strings.py` deriva los `Localizable.strings` de iOS. Pruebas verifican mismas claves y mismos argumentos en ambos idiomas.
