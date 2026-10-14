# Limitaciones por plataforma (y alternativa implementada)

## Comunes
| Limitación | Alternativa en SPACE AI |
|---|---|
| Ninguna app puede borrar la caché de otras apps (WhatsApp, Instagram, TikTok…) | Se muestra como "Restringido por el sistema" y se dan instrucciones verificadas para hacerlo a mano |
| "Acelerar RAM/batería" no es real en sistemas modernos | No se ofrece ni se promete |
| Lo enviado a papelera aún ocupa espacio | Se reporta por separado de "espacio liberado (medido)" |
| El espacio libre puede cambiar por otras apps durante la limpieza | Estado `UNVERIFIED` honesto si la medición no respalda lo borrado |

## Android
| Limitación | Detalle | Alternativa |
|---|---|---|
| Acceso general a archivos | `MANAGE_EXTERNAL_STORAGE` está restringido por Google Play para limpiadores | Carpetas elegidas por el usuario vía SAF |
| Raíz de *Download* y del almacenamiento | Android 11+ no permite elegirlas en el selector | Elegir subcarpetas u otras carpetas; se explica en la app |
| Borrar medios de otras apps | Requiere diálogo del sistema | Se agrupan hasta 300 por diálogo (Android 11+); en Android 10 el sistema pide confirmación **por archivo** |
| Papelera del sistema | Disponible desde Android 11 | Modo configurable: papelera (por defecto, recuperable) o definitivo; en Android 10 solo definitivo |
| Acceso parcial a fotos (Android 14) | MediaStore solo devuelve lo elegido | Se analiza solo eso y se indica "Limitado" |
| Compresión | Depende del codificador de hardware del fabricante | Media3 Transformer elige codificador; se valida la salida real |
| Miniaturas | `loadThumbnail` puede fallar en archivos dañados | Se cuentan como ilegibles, no se sugieren |

## iOS
| Limitación | Detalle | Alternativa |
|---|---|---|
| Sandbox estricto | No hay acceso a archivos de otras apps ni al sistema | Fotos vía PhotoKit; documentos solo en carpetas elegidas en Archivos |
| Borrado de fotos | iOS siempre muestra su diálogo y mueve a "Eliminados recientemente" (30 días) | Estado "Movido a Eliminados recientemente"; se indica cómo vaciarlo en Fotos (las apps no pueden vaciar ese álbum) |
| Fotos de iCloud | Con Fotos de iCloud, borrar elimina en todos los dispositivos; originales "optimizados" no están en el iPhone | Advertencia en la confirmación; el análisis no descarga de iCloud y reporta esos elementos como "No están en este iPhone" |
| Tamaño de cada foto | PhotoKit no tiene API pública documentada | Se lee `fileSize` del recurso original (`PHAssetResource`); si no existe, el elemento se omite en vez de inventar un tamaño. **Riesgo de revisión:** es KVC no documentado; si Apple lo objeta, alternativa: medir leyendo el recurso (más lento) |
| Espacio libre | iOS reporta capacidad "para uso importante" (incluye purgable) | Es la misma cifra que Ajustes; puede crecer tras borrar con retraso |
| Compresión | `AVAssetExportSession` usa presets, no bitrate exacto | Estimación del motor + medición real de la copia antes de conservarla |
| Videos solo en iCloud | No se pueden comprimir sin descargarlos | Se indica que debe estar descargado |
| Caché de otras apps | Imposible | Guía: Ajustes > General > Almacenamiento del iPhone > Desinstalar app |
