# Reporte de pruebas

## 1. Ejecutado en este entorno (resultado real)

**Motor central Kotlin** — compilado con `kotlinc 2.0.21` (JDK 21) y ejecutado con `tools/offline-test/run.sh` (los mismos archivos de `android/core/src/test`; se usó un stub mínimo de `org.junit` porque Maven está bloqueado en este entorno).

**Resultado: 59 aprobadas, 0 fallidas.** Log completo: [`evidencia/core-tests-local.log`](evidencia/core-tests-local.log).

| Suite | Pruebas | Cubre |
|---|---|---|
| Sha256Test | 4 | Vector conocido, streaming = memoria (1 MB), prefijo, cancelación |
| ExactDuplicateFinderTest | 9 | Biblioteca vacía, duplicados, **falso duplicado** (mismo tamaño y prefijo, distinto final), no abre tamaños únicos, archivos ilegibles/corruptos, favorita conservada, caché sin relecturas, cancelación |
| HashCacheTest | 3 | Persistencia atómica, invalidación al modificar, archivo corrupto ignorado (**protección ante cierres inesperados**) |
| PerceptualHashTest | 5 | Copia reescalada ≈ idéntica, cambio de brillo, 30 escenas distintas lejos, hex, conversión ARGB/RGBA |
| QualityAnalyzerTest | 5 | Borrosa detectada (nitidez ×5 menor), oscura, puntuación, códec, imágenes 1×1 |
| SimilarityGrouperTest | 9 | Agrupa y conserva la más nítida, capturas excluidas, ventana temporal, relación de aspecto, ráfagas, sensibilidad, BK-tree = fuerza bruta (3000 hashes), **10 000 fotos en ~170 ms** |
| VideoCompressionTest | 7 | 4K→720p rentable, video eficiente no se ofrece, nunca sobre el bitrate fuente, dimensiones pares, duración 0, validador (no decodificable, duración, no más pequeño, vacío), margen de espacio |
| ScanAnalyzerTest | 4 | Teléfono vacío = 0 (sin cifras inventadas), cada archivo se cuenta una vez, favoritas protegidas, riesgos por categoría |
| SafeCleanupTest | 11 | Riesgo medio/alto sin aprobación → bloqueado, aprobación de otro plan, aprobación insuficiente, no borrar todas las copias, protegido, papelera ≠ liberado, sin aumento medido → "no verificado", fallas parciales registradas, usuario rechaza diálogo, cancelación antes de iniciar |
| ByteFormatTest | 2 | Base 1000 como el sistema, locales, medición inválida |

### Pruebas de mutación (las pruebas sí detectan errores reales)
Se rompió deliberadamente el código y se volvieron a correr las pruebas:

| Mutación | Resultado |
|---|---|
| Quitar la verificación de aprobación en `SafeCleanupEngine` | 3 pruebas fallan ✔ |
| Ampliar el radio de casi-exactas de 2 a 64 bits | 3 pruebas fallan ✔ |

## 2. Ejecutadas en CI — todas aprobadas (runs 38579221215 Android y 38579221239 iOS, 14 oct 2026)

La CI encontró y se corrigieron: 2 errores de compilación Swift, 1 regla de lint, 1 falla de prueba iOS (clave del historial sin Llavero) y 1 **bug real de Android**: MediaStore reporta tamaño 0 justo después de escribir un archivo y el escáner lo omitía; ahora mide el tamaño real.

| Suite | Dónde | Qué valida |
|---|---|---|
| `:core:test` (las mismas 59) | `android.yml` | Igual que arriba con JUnit real |
| `StringsParityTest` | `android.yml` | es-MX e inglés con mismas claves y argumentos |
| `lintDebug` | `android.yml` | API levels, permisos, accesibilidad, recursos |
| Verificación de APK | `android.yml` | El APK no contiene `INTERNET`, `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`; SHA-256 de binarios |
| `DeviceIntegrationTest` | Emulador API 29 y 34 | Inserta fotos reales en MediaStore, las escanea, detecta duplicado, calcula dHash, **borra y verifica** con MediaStore, medidor real, historial cifrado en disco |
| `HomeScreenUiTest` | Emulador | Botón ANALIZAR, progreso con conteo real, cancelar |
| `SpaceAICoreTests` (swift test) | `ios.yml` (macOS) | Las mismas reglas del motor en Swift (~40 pruebas) |
| `DeviceServicesTests` | Simulador iOS | Medidor real, historial cifrado (no texto plano), conversión CGImage, temporales propios, paridad de idiomas |
| `LaunchUITests` | Simulador iOS | Pantalla inicial en es-MX + captura adjunta |

## 3. Matriz de escenarios solicitados

| Escenario | Estado |
|---|---|
| Biblioteca vacía | ✔ local (motor) |
| Miles de fotografías | ✔ local (10 000 firmas) · CI en dispositivo para miniaturas |
| Videos grandes | ✔ local (estimación/validación) · compresión real: prueba manual en dispositivo pendiente |
| Archivos corruptos | ✔ local (ilegibles reportados, no agrupados) |
| Duplicados falsos | ✔ local |
| Permisos denegados / limitados | Lógica en app; estado reportado. Verificación manual pendiente en dispositivo |
| Almacenamiento casi lleno / poco espacio al comprimir | ✔ local (margen requerido) · `InsufficientSpaceException` en app |
| Interrupción del análisis | ✔ local (cancelación + caché atómica) |
| Cancelación de limpieza | ✔ local |
| Sin conexión | Por diseño: Android sin permiso de red; verificado en CI sobre el APK |
| Bajo rendimiento | Concurrencia limitada a 3; miniaturas de 512 px. Medición en gama media pendiente |

## 4. Pendiente antes de publicar (requiere dispositivo físico)
2. Prueba manual en un Android gama media (Android 10 y 14) y un iPhone (iOS 16+): análisis de ≥5 000 fotos, limpieza de duplicados, compresión de un video 4K, permisos limitados y denegados.
3. Revisar tiempos y consumo con Android Studio Profiler / Instruments.
