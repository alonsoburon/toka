#!/usr/bin/env bash
# Publica el APK release de Toka en el repositorio F-Droid "Nuxapower Apps" (~/code/nuxapower-apps).
# Uso: scripts/publicar-apk.sh [ruta.apk]   (por defecto, el APK release recién compilado; no compila)
#
# Compila antes con la versión del tag, no con la de desarrollo (versionCode 1, que F-Droid vería como
# más vieja que cualquier release):
#   cd android && ./gradlew assembleRelease -PtokaVersionName=0.3.0 -PtokaVersionCode=300
# versionCode = major*10000 + minor*100 + patch, igual que el workflow de release.
set -euo pipefail
cd "$(dirname "$0")/.."
exec "$HOME/code/nuxapower-apps/publicar.sh" "$(realpath "${1:-android/app/build/outputs/apk/release/app-release.apk}")"
