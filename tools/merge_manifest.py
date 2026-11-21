#!/usr/bin/env python3
"""Минимальное слияние манифестов для build.sh (Gradle делает это сам).

merge_manifest.py main.xml [flavor.xml] out.xml package versionCode versionName

Из манифеста флейвора добавляются: элементы верхнего уровня (uses-permission и т. п.),
дочерние элементы <application>; для <activity> с тем же android:name — атрибуты и
вложенные элементы дописываются в существующую activity.
"""
import sys
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', A[1:-1])


def main():
    args = sys.argv[1:]
    main_path, rest = args[0], args[1:]
    flavor_path = rest.pop(0) if len(rest) == 5 else None
    out_path, package, vcode, vname = rest

    tree = ET.parse(main_path)
    root = tree.getroot()
    app = root.find('application')

    if flavor_path:
        froot = ET.parse(flavor_path).getroot()
        for el in froot:
            if el.tag != 'application':
                root.insert(list(root).index(app), el)
        fapp = froot.find('application')
        if fapp is not None:
            for k, v in fapp.attrib.items():
                app.set(k, v)
            for el in fapp:
                name = el.get(A + 'name')
                same = None
                for existing in app.findall(el.tag):
                    if name and existing.get(A + 'name') == name:
                        same = existing
                if same is not None:
                    for k, v in el.attrib.items():
                        same.set(k, v)
                    for child in el:
                        same.append(child)
                else:
                    app.append(el)

    root.set('package', package)
    root.set(A + 'versionCode', vcode)
    root.set(A + 'versionName', vname)
    tree.write(out_path, encoding='utf-8', xml_declaration=True)


if __name__ == '__main__':
    main()
