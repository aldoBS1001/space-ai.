#!/usr/bin/env python3
"""Ícono de SPACE AI (anillo de almacenamiento + destello) generado de forma reproducible."""
import os, math
from PIL import Image, ImageDraw
def icon(size=1024):
    s = 4; S = size * s
    img = Image.new("RGB", (S, S), (14, 21, 48))
    d = ImageDraw.Draw(img)
    # degradado vertical sutil
    for y in range(S):
        t = y / S
        d.line([(0, y), (S, y)], fill=(int(14 + 10 * t), int(21 + 14 * t), int(48 + 40 * t)))
    c = S / 2; r = S * 0.30; w = int(S * 0.075)
    box = [c - r, c - r, c + r, c + r]
    d.arc(box, 0, 360, fill=(255, 255, 255, 60) if False else (52, 62, 98), width=w)
    d.arc(box, -90, 200, fill=(91, 140, 255), width=w)
    # extremos redondeados
    for ang in (-90, 200):
        a = math.radians(ang); x = c + r * math.cos(a) - 0; y = c + r * math.sin(a)
        rr = w / 2 - 1
        # centro del trazo está en r - w/2 desde el borde del box
        x = c + (r - w / 2) * math.cos(a); y = c + (r - w / 2) * math.sin(a)
        d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=(91, 140, 255))
    # destello
    k = S * 0.085
    pts = [(c, c - 2 * k), (c + 0.5 * k, c - 0.5 * k), (c + 2 * k, c), (c + 0.5 * k, c + 0.5 * k),
           (c, c + 2 * k), (c - 0.5 * k, c + 0.5 * k), (c - 2 * k, c), (c - 0.5 * k, c - 0.5 * k)]
    d.polygon(pts, fill=(255, 255, 255))
    return img.resize((size, size), Image.LANCZOS)
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
icon().save(os.path.join(root, "ios/SpaceAI/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"))
icon(512).save(os.path.join(root, "design/icon-512-play-store.png"))
print("ok")
