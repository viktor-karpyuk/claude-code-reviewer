#!/bin/bash
# Aplica uno de los tres iconos como el icono oficial de la app.
#   ./aplicar.sh a    (lupa sobre el diff)
#   ./aplicar.sh b    (comentario aprobado)
#   ./aplicar.sh c    (pull request + IA)
set -e
cd "$(dirname "$0")"

case "$1" in
  a) SRC=a-lupa ;;
  b) SRC=b-comentario ;;
  c) SRC=c-pr ;;
  *) echo "uso: $0 {a|b|c}"; exit 1 ;;
esac

cp "$SRC.icns"      ../acr-icon.icns
cp "$SRC-1024.png"  ../acr-icon.png
cp "$SRC-1024.png"  ../../src/main/resources/icon.png
# .ico para el empaquetado de Windows: 256 px alcanza
rsvg-convert -w 256 -h 256 "$SRC.svg" -o ../acr-icon.ico 2>/dev/null || \
  cp "$SRC-128.png" ../acr-icon.ico

echo "Icono '$SRC' aplicado."
echo "Verificá con:  ./gradlew run     |     empaquetá con:  ./gradlew packageDmg"
