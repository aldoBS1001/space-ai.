# SPACE AI — Smart Phone Cleaner

**Tu espacio, bajo control.** Limpiador inteligente de almacenamiento para iPhone y Android: encuentra duplicados exactos, fotos similares, borrosas y oscuras, videos grandes y comprimibles, y archivos pesados — todo en el dispositivo, sin internet, y nunca borra nada sin que el usuario lo revise y confirme.

> Un producto de **Business Shop A&A** — el logotipo aparece en la pantalla de inicio y en Configuración › Acerca de (variantes clara y oscura en `design/`). Bundle/paquete: `com.businessshop.spaceai`.

## Estado real (14 oct 2026) — CI en verde

| Componente | Estado | Evidencia |
|---|---|---|
| Motor central Kotlin | **59/59 pruebas** (local y en CI) | `docs/evidencia/core-tests-local.log`, workflow Android |
| App Android | **Compila; lint sin errores; APK debug, APK y AAB release generados**; sin permiso de internet verificado en el APK | Run [38579221215](https://github.com/aldoBS1001/space-ai./actions/runs/38579221215), artefacto `spaceai-android` |
| Pruebas en emulador Android 10 (API 29) y 14 (API 34) | **Aprobadas** (MediaStore real, borrado verificado, Keystore, UI) | Artefactos `spaceai-android-device-tests-*` |
| Motor Swift (40 pruebas) + app iOS | **Compila; pruebas unitarias y de interfaz aprobadas en simulador; build Release para dispositivo OK** | Run 38579221239, artefacto `spaceai-ios` |
| AAB firmado / IPA | Pendiente de cargar los secretos de firma (keystore y certificado de Apple) | `docs/MANUAL_INSTALACION.md` |

## Estructura

```
android/            Proyecto Android Studio (Gradle 8.14.3, AGP 8.7, Kotlin 2.0)
  core/             Motor central en Kotlin puro (JVM) + pruebas
  app/              App: Compose, MediaStore, SAF, Media3 Transformer, Keystore
ios/                Proyecto iOS (XcodeGen → Xcode 16)
  SpaceAICore/      Motor central en Swift (paquete SPM) + pruebas (misma lógica que Android)
  SpaceAI/          App: SwiftUI, PhotoKit, Vision, AVFoundation, CryptoKit
  SpaceAITests/     Pruebas de servicios de plataforma
  SpaceAIUITests/   Prueba de interfaz
.github/workflows/  Compilación y pruebas reales (Android + iOS)
docs/               Manual, documentación técnica, privacidad, permisos, limitaciones, pruebas
tools/              Generadores (textos es-MX/en, ícono) y runner de pruebas sin red
design/             Ícono 512 px para Google Play
```

## Empezar

- **Obtener APK/AAB/IPA:** sube este repositorio a GitHub y ejecuta los workflows *Android* e *iOS* (pestaña Actions). Detalle en [`docs/MANUAL_INSTALACION.md`](docs/MANUAL_INSTALACION.md).
- **Abrir en Android Studio:** carpeta `android/`.
- **Abrir en Xcode:** `cd ios && xcodegen generate && open SpaceAI.xcodeproj`.

## Documentación

- [Manual de instalación](docs/MANUAL_INSTALACION.md)
- [Documentación técnica](docs/DOCUMENTACION_TECNICA.md)
- [Política de privacidad](docs/POLITICA_PRIVACIDAD.md)
- [Inventario de permisos](docs/INVENTARIO_PERMISOS.md)
- [Limitaciones por plataforma](docs/LIMITACIONES_PLATAFORMA.md)
- [Reporte de pruebas](docs/REPORTE_PRUEBAS.md)
- [Publicación en tiendas](docs/PUBLICACION.md)
