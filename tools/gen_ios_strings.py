#!/usr/bin/env python3
"""Genera Localizable.strings (en, es-MX) para iOS a partir de la tabla de Android
más los textos propios de iOS. Convierte %1$s -> %1$@ y %1$d -> %1$ld."""
import os, re, importlib.util
here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("a", os.path.join(here, "gen_android_strings.py"))
src = open(os.path.join(here, "gen_android_strings.py"), encoding="utf-8").read()
S = {}
exec(src.split("def esc")[0], {"__file__": os.path.join(here, "gen_android_strings.py"), "os": os, "html": None, "S": S}, g := {})
S = g["S"]

def conv(t):
    t = re.sub(r"%(\d+)\$s", r"%\1$@", t)
    t = re.sub(r"%(\d+)\$d", r"%\1$ld", t)
    return t

out = {k: (conv(v[0]), conv(v[1])) for k, v in S.items()}
# Categorías y estados con los nombres de enum de Swift.
cat_map = {"exactDuplicatePhotos": "cat_exact_photos", "similarPhotos": "cat_similar", "burstPhotos": "cat_burst",
    "screenshots": "cat_screenshots", "blurryPhotos": "cat_blurry", "darkPhotos": "cat_dark", "lowQualityPhotos": "cat_low_quality",
    "oldLargePhotos": "cat_old_large_photos", "largeVideos": "cat_large_videos", "duplicateVideos": "cat_duplicate_videos",
    "screenRecordings": "cat_screen_recordings", "oldVideos": "cat_old_videos", "compressibleVideos": "cat_compressible",
    "largeFiles": "cat_large_files", "duplicateFiles": "cat_duplicate_files", "appTempFiles": "cat_app_temp"}
for swift, akey in cat_map.items(): out["cat_" + swift] = out[akey]
for swift, akey in {"full": "access_full", "limited": "access_limited", "denied": "access_denied",
                    "restrictedByOS": "access_restricted", "notRequested": "access_not_requested"}.items():
    out["access_" + swift] = out[akey]
out["access_restrictedByOS"] = ("Restricted by iOS", "Restringido por iOS")
for swift, akey in {"verifiedFreed": "status_verified", "pendingSystemTrash": "status_trash", "unverified": "status_unverified",
                    "partialFailure": "status_partial", "nothingDeleted": "status_nothing", "cancelled": "status_cancelled"}.items():
    out["status_" + swift] = out[akey]; out["status_" + swift + "_body"] = out[akey + "_body"]
out["status_pendingSystemTrash"] = ("Moved to Recently Deleted", "Movido a Eliminados recientemente")
out["status_pendingSystemTrash_body"] = (
    "iOS keeps deleted photos and videos in Recently Deleted for 30 days. Space is freed when you empty it in Photos > Albums > Recently Deleted.",
    "iOS conserva las fotos y videos eliminados en «Eliminados recientemente» durante 30 días. El espacio se libera al vaciarlo en Fotos > Álbumes > Eliminados recientemente.")
ios = {
    "permission_denied_note_ios": ("Photo access was denied. Enable it in Settings > SPACE AI > Photos to analyze your library.",
                                   "Se negó el acceso a Fotos. Actívalo en Ajustes > SPACE AI > Fotos para analizar tu fototeca."),
    "open_settings": ("Open Settings", "Abrir Ajustes"),
    "unreadable_ios": ("Not on this iPhone (iCloud) or unreadable", "No están en este iPhone (iCloud) o ilegibles"),
    "other_apps_guide_body_ios": ("iOS does not allow any app to clear another app's cache. To free space yourself: Settings > General > iPhone Storage, choose the app and use Offload App (keeps its data) or delete content inside the app (for example WhatsApp > Settings > Storage and Data > Manage Storage).",
                                  "iOS no permite que una app borre la caché de otra. Para liberar espacio tú mismo: Ajustes > General > Almacenamiento del iPhone, elige la app y usa «Desinstalar app» (conserva sus datos) o borra contenido dentro de la app (por ejemplo WhatsApp > Configuración > Almacenamiento y datos > Administrar almacenamiento)."),
    "review_hint_groups_ios": ("The ★ photo is kept. Long-press another photo to keep it instead. Tap to select or deselect.",
                               "Se conserva la foto con ★. Mantén presionada otra foto para conservarla en su lugar. Toca para seleccionar o quitar."),
    "keep_this": ("Keep this one", "Conservar esta"),
    "not_deletable_ios": ("iOS does not allow deleting this item", "iOS no permite eliminar este elemento"),
    "mode_recently_deleted_ios": ("iOS will ask you to confirm and move the items to Recently Deleted (recoverable for 30 days). Space is freed when that album is emptied.",
                                  "iOS te pedirá confirmar y moverá los elementos a «Eliminados recientemente» (recuperables 30 días). El espacio se libera al vaciar ese álbum."),
    "icloud_warning_ios": ("If iCloud Photos is on, deleting here also deletes from iCloud and your other devices.",
                           "Si Fotos de iCloud está activado, eliminar aquí también elimina de iCloud y de tus otros dispositivos."),
    "delete_n_ios": ("Delete %1$ld items", "Eliminar %1$ld elementos"),
    "recently_deleted_count": ("In Recently Deleted", "En Eliminados recientemente"),
    "recently_deleted_explainer": ("To free that space now, open Photos > Albums > Recently Deleted and delete them. iOS does not let apps empty that album.",
                                   "Para liberar ese espacio ahora, abre Fotos > Álbumes > Eliminados recientemente y bórralos. iOS no permite que las apps vacíen ese álbum."),
    "reading_video_ios": ("Reading video (it must be downloaded on this iPhone)…", "Leyendo video (debe estar descargado en este iPhone)…"),
    "copy_saved_ios": ("The compressed copy was saved to Photos. Your original is still intact.", "La copia comprimida se guardó en Fotos. Tu original sigue intacto."),
    "folders_help_ios": ("Choose folders in Files (On My iPhone, iCloud Drive or other providers). SPACE AI only sees the folders you add.",
                         "Elige carpetas en Archivos (En mi iPhone, iCloud Drive u otros proveedores). SPACE AI solo ve las carpetas que agregues."),
    "privacy_body": ("SPACE AI works without internet: photos, videos and documents never leave your iPhone. It stores only hashes and cleanup metrics, encrypted, on this device. It does not show ads or promise RAM or battery boosts.",
                     "SPACE AI funciona sin internet: fotos, videos y documentos nunca salen de tu iPhone. Solo guarda hashes y métricas de limpieza, cifradas, en este dispositivo. No muestra anuncios ni promete acelerar RAM o batería."),
}
out.update(ios)
for k in ["delete_n", "move_to_trash_n", "mode_trash_note", "mode_permanent_note", "other_apps_guide_body_android", "system_dialog_note",
          "permission_denied_note", "trash_explainer", "empty_trash_now", "mode_trash", "mode_permanent", "delete_mode", "open_storage_settings",
          "copy_saved", "folders_help", "not_deletable", "reading_video"]:
    out.pop(k, None)

def esc(t): return t.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
root = os.path.join(here, "..", "ios", "SpaceAI", "Resources")
for idx, folder in ((0, "en.lproj"), (1, "es-MX.lproj")):
    os.makedirs(os.path.join(root, folder), exist_ok=True)
    with open(os.path.join(root, folder, "Localizable.strings"), "w", encoding="utf-8") as f:
        f.write("/* Generado por tools/gen_ios_strings.py: no editar a mano. */\n")
        for k in sorted(out): f.write(f'"{k}" = "{esc(out[k][idx])}";\n')
    perms = {
        0: ("SPACE AI analyzes your photos and videos on this iPhone to find duplicates, similar shots and large videos. Nothing is uploaded.",
            "SPACE AI saves compressed copies of your videos to your library only when you confirm."),
        1: ("SPACE AI analiza tus fotos y videos en este iPhone para encontrar duplicados, fotos similares y videos grandes. No se sube nada.",
            "SPACE AI guarda copias comprimidas de tus videos en tu fototeca solo cuando tú lo confirmas."),
    }[idx]
    with open(os.path.join(root, folder, "InfoPlist.strings"), "w", encoding="utf-8") as f:
        f.write(f'"NSPhotoLibraryUsageDescription" = "{esc(perms[0])}";\n"NSPhotoLibraryAddUsageDescription" = "{esc(perms[1])}";\n"CFBundleDisplayName" = "SPACE AI";\n')
print(len(out), "claves iOS")
