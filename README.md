# SPACE AI — Smart Phone Cleaner

**Tu espacio, bajo control.** Limpiador inteligente de almacenamiento para iPhone y Android: encuentra duplicados exactos, fotos similares, borrosas y oscuras, videos grandes y comprimibles, y archivos pesados — todo en el dispositivo, sin internet, y nunca borra nada sin que el usuario lo revise y confirme.

> Un producto de **Business Shop A&A** — el logotipo aparece en la pantalla de inicio y en Configuración › Acerca de (variantes clara y oscura en `design/`). Bundle/paquete: `com.businessshop.spaceai`.

## Estado real (14 oct 2026)

| Componente | Estado | Evidencia |
|---|---|---|
| Motor central Kotlin (duplicados, similares, calidad, riesgo, recomendaciones, eliminación segura, compresión) | **Compilado y probado: 59/59 pruebas** | `docs/evidencia/core-tests-local.log` |
| Pruebas de mutación (romper reglas de seguridad → las pruebas fallan) | **Verificado** | `docs/REPORTE_PRUEBAS.md` |
| App Android (Compose, MediaStore, SAF, Media3, Keystore) | Código completo, **no compilado aquí** | Se compila en GitHub Actions (`android.yml`) |
| APK de prueba / AAB | **No generados aquí** | Los genera `android.yml` (artefacto `spaceai-android`) |
| Motor Swift + app iOS (SwiftUI, PhotoKit, Vision, AVFoundation) | Código completo, **no compilado aquí** | Se compila y prueba en macOS con `ios.yml` |
| IPA firmada | Requiere tu certificado y perfil de Apple | `ios.yml` la genera si cargas los secretos |

El entorno donde se construyó bloquea el SDK de Android, Maven/Google y no tiene Xcode, por eso los binarios se producen en CI real. **Ningún APK, AAB o IPA se declara listo hasta que el workflow correspondiente termine en verde.**

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
