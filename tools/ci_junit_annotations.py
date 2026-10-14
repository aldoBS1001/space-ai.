#!/usr/bin/env python3
"""Convierte fallas de reportes JUnit XML en anotaciones ::error de GitHub Actions."""
import glob, sys, xml.etree.ElementTree as ET
n = 0
for f in glob.glob(sys.argv[1], recursive=True):
    for tc in ET.parse(f).getroot().iter("testcase"):
        for fail in list(tc.findall("failure")) + list(tc.findall("error")):
            text = (fail.get("message") or fail.text or "").strip().replace("\n", " | ")[:600]
            print(f"::error title={tc.get('classname','')}.{tc.get('name','')}::{text}")
            n += 1
print(f"{n} fallas reportadas")
