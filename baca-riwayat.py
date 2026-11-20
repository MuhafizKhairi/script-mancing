#!/usr/bin/env python3
"""Cetak isi sesi riwayat (.am.gz), juga yang masih terbuka/terpotong: python3 baca-riwayat.py riwayat/<...>/sesi-*.am.gz"""
import sys, zlib
for p in sys.argv[1:]:
    o = zlib.decompressobj(16 + zlib.MAX_WBITS)
    sys.stdout.write(o.decompress(open(p, 'rb').read()).decode('utf-8', 'replace'))
