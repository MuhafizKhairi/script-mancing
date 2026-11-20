#!/bin/bash
# Tarik riwayat Auto Mancing (versi dev) dari HP yang tercolok ke riwayat/<serial>-<waktu>/.
#   ./ambil-riwayat.sh          tarik saja
#   ./ambil-riwayat.sh --hapus  tarik, lalu hapus riwayat di HP (kalau sudah dicek)
# Baca log: python3 baca-riwayat.py riwayat/<...>/sesi-*.am.gz   (format dijelaskan di baris # paling atas tiap file)
set -euo pipefail
cd "$(dirname "$0")"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SRC=/sdcard/Android/data/com.autofish.wibu/files/riwayat
SER="$("$ADB" get-serialno)"
OUT="riwayat/$SER-$(date +%Y%m%d-%H%M)"
mkdir -p "$OUT"
"$ADB" pull "$SRC/." "$OUT/"
echo "Disimpan di $OUT ($(du -sh "$OUT" | cut -f1))"
if [ "${1:-}" = "--hapus" ]; then
    "$ADB" shell "rm -rf $SRC/*"
    echo "Riwayat di HP dihapus."
fi
