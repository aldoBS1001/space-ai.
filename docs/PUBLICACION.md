# Publicación en tiendas

## Google Play
1. Play Console → crear app "SPACE AI" (categoría Herramientas), paquete `com.businessshop.spaceai`.
2. Activar **Play App Signing** y subir `app-release.aab` firmado con la clave de subida.
3. **Seguridad de los datos:** no se recopilan ni comparten datos; los datos no salen del dispositivo.
4. **Declaración de permisos:** no aplica `MANAGE_EXTERNAL_STORAGE` ni `QUERY_ALL_PACKAGES`. Fotos y videos: declarar uso principal (la app es un gestor/limpiador de medios), conforme a la política de permisos de fotos y videos.
5. Ficha: ícono `design/icon-512-play-store.png`, gráfico de funciones 1024×500, capturas reales del teléfono. Evitar textos tipo "acelera tu teléfono" o "limpia RAM": Google los rechaza como engañosos y la app no los ofrece.
6. Probar primero en **pista de prueba interna/cerrada** (en cuentas personales nuevas Google exige prueba cerrada con testers antes de producción).
7. Política de privacidad publicada en una URL pública.

## App Store
1. Cuenta Apple Developer de Business Shop A&A → Identifiers → `com.businessshop.spaceai`.
2. App Store Connect → nueva app, idioma principal Español (México), localización en inglés.
3. Subir build (Xcode Archive o workflow con secretos) → TestFlight.
4. **Privacidad de la app:** "Datos no recopilados". `PrivacyInfo.xcprivacy` ya incluido.
5. Notas para revisión (sugeridas):
   > SPACE AI analiza la fototeca en el dispositivo para encontrar duplicados, fotos similares y videos grandes. No hay cuenta ni conexión a red. Toda eliminación la confirma el usuario y iOS muestra su propio diálogo; los elementos van a "Eliminados recientemente". La compresión de video crea una copia nueva; el original solo se elimina si el usuario lo confirma después de reproducir la copia.
6. Guía 2.3 / 5.1.1: no prometer liberar caché de otras apps ni acelerar el sistema (la app no lo hace).
7. Capturas 6.9" y 6.5" (y iPad si se mantiene universal).

## Lista de verificación previa
- [ ] Workflows Android e iOS en verde.
- [ ] Pruebas manuales en dispositivos físicos (ver `REPORTE_PRUEBAS.md` §4).
- [ ] Política de privacidad con datos legales completos y URL pública.
- [ ] Versionado: `versionCode`/`CURRENT_PROJECT_VERSION` incrementales (el CI usa el número de ejecución).
