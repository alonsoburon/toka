#!/usr/bin/env python3
"""Genera el logo de Toka (casa + check) en todos sus formatos desde una sola descripción de capas.

Lenguaje visual (rediseño de íconos 2026 de Google y Microsoft): sin baldosa ni contenedor; un glifo grande,
muy redondeado y de una sola pieza que llena el ícono, con degradado suave de varios colores, fondo claro y solo
un toque de profundidad (brillo + sombra de suelo). Las sombras son capas translúcidas apiladas porque un vector
de Android no tiene desenfoque.

Salidas:
  design/logo.svg, design/icon-512.png                       master y PNG de 512 px
  android/.../res/drawable/ic_launcher_background.xml        fondo claro con brillo
  android/.../res/drawable/ic_launcher_foreground.xml        casa + check
  android/.../res/drawable/ic_launcher_monochrome.xml        silueta de un color (íconos temáticos, Android 13+)
  android/.../res/drawable/ic_logo.xml                       glifo sin fondo para la pantalla de entrada
  android/.../res/drawable/ic_notification.xml               silueta blanca para la barra de estado
Uso: python3 design/make_logo.py
"""
import math, pathlib, subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "android/app/src/main/res"

# Paleta del degradado (coral → rosa → violeta): brillante y con ~40° de cambio de tono, como los íconos nuevos.
G_STOPS = [(0.0, "#FFB05C"), (0.42, "#F2609F"), (1.0, "#6F5BF4")]

# ── Geometría (lienzo 108; el glifo cabe en el círculo seguro del ícono adaptativo) ──
HOUSE = ("M54,19Q56.6,19 58.7,21L82,43Q85,46 85,50V68Q85,82 71,82H37Q23,82 23,68V50Q23,46 26,43L49.3,21Q51.4,19 54,19Z")
CHECK_PTS = [(40.5, 63), (49.5, 72), (68.5, 53)]
CHECK_W = 8.2
ELLIPSE = lambda cx, cy, rx, ry: f"M{cx-rx},{cy}a{rx},{ry} 0 1 1 {2*rx},0a{rx},{ry} 0 1 1 {-2*rx},0"
CHECK_PATH = "M" + "L".join(f"{x},{y}" for x, y in CHECK_PTS)

# El launcher recorta con una máscara de diámetro 72 sobre el lienzo de 108 (circular, squircle...): el glifo del
# ícono adaptativo va al 82 % para dejar aire, como los íconos de Google. La pantalla de entrada usa su propia escala.
FG_SCALE, FG_PIVOT = 0.82, (54, 53)

# Capas: (path, fill, stroke, extra). fill: "#AARRGGBB"/"#RRGGBB" | ("lin", x0,y0,x1,y1,stops) | ("rad", cx,cy,r,stops)
# stops: [(offset, color), ...]. stroke: (color, width, join, cap) o None. extra "t": traslación (dx, dy).
BG_LAYERS = [
    ("M0,0h108v108h-108z", ("rad", 26, 12, 110, [(0, "#FFFFFF"), (1, "#F1EBFF")]), None, {}),
]
FG_LAYERS = [
    # sombra de suelo (elipses apiladas = falso desenfoque)
    (ELLIPSE(54, 85.6, 25, 3.8), "#0A5B3FA0", None, {}),
    (ELLIPSE(54, 85.2, 20, 2.9), "#0F5B3FA0", None, {}),
    (ELLIPSE(54, 84.9, 14, 2.0), "#165B3FA0", None, {}),
    # el glifo
    (HOUSE, ("lin", 20, 18, 88, 84, G_STOPS), None, {}),
    # volumen: reflejo arriba y sombreado suave abajo a la derecha
    (HOUSE, ("rad", 40, 28, 46, [(0, "#66FFFFFF"), (1, "#00FFFFFF")]), None, {}),
    (HOUSE, ("lin", 40, 40, 86, 84, [(0, "#00302070"), (1, "#33302070")]), None, {}),
    # el check en relieve: sombra mínima + trazo blanco
    (CHECK_PATH, "#00000000", ("#26401060", CHECK_W + 1.2, "round", "round"), {"t": (0, 1.6)}),
    (CHECK_PATH, "#00000000", ("#FFFFFF", CHECK_W, "round", "round"), {}),
]
# Para la pantalla de entrada: solo el glifo (sin fondo), así luce sobre cualquier color de pantalla.
LOGO_LAYERS = FG_LAYERS

# ── Silueta de un solo color (monocromo y notificación): casa con el check como agujero (evenOdd) ──
def check_polygon(pts, w):
    h = w / 2
    def unit(a, b):
        dx, dy = b[0] - a[0], b[1] - a[1]; n = math.hypot(dx, dy); return dx / n, dy / n
    u1, u2 = unit(pts[0], pts[1]), unit(pts[1], pts[2])
    n1, n2 = (-u1[1], u1[0]), (-u2[1], u2[0])
    A, B, C = pts
    def miter(P, sign):
        mx, my = n1[0] + n2[0], n1[1] + n2[1]
        k = h / (1 + n1[0] * n2[0] + n1[1] * n2[1])
        return P[0] + sign * mx * k, P[1] + sign * my * k
    left = [(A[0] + n1[0] * h, A[1] + n1[1] * h), miter(B, 1), (C[0] + n2[0] * h, C[1] + n2[1] * h)]
    right = [(C[0] - n2[0] * h, C[1] - n2[1] * h), miter(B, -1), (A[0] - n1[0] * h, A[1] - n1[1] * h)]
    return left + right
def poly(points): return "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in points) + "Z"
SILHOUETTE = HOUSE + poly(check_polygon(CHECK_PTS, CHECK_W))

# ── SVG ──
def argb(c):
    c = c.lstrip("#")
    return ("#" + c[2:], int(c[:2], 16) / 255) if len(c) == 8 else ("#" + c, 1.0)

_GID = [0]
class Svg:
    def __init__(self): self.defs, self.body = [], []
    def stops(self, stops):
        out = ""
        for off, c in stops:
            k, a = argb(c); out += f'<stop offset="{off}" stop-color="{k}" stop-opacity="{a:.3f}"/>'
        return out
    def paint(self, fill):
        if isinstance(fill, str):
            return argb(fill)
        _GID[0] += 1; gid = f"g{_GID[0]}"
        if fill[0] == "lin":
            _, x0, y0, x1, y1, st = fill
            self.defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x0}" y1="{y0}" x2="{x1}" y2="{y1}">{self.stops(st)}</linearGradient>')
        else:
            _, cx, cy, r, st = fill
            self.defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">{self.stops(st)}</radialGradient>')
        return f"url(#{gid})", 1.0
    def layer(self, d, fill, stroke, extra):
        f, fa = self.paint(fill)
        attrs = f'd="{d}" fill="{f}" fill-opacity="{fa:.3f}"'
        if stroke:
            sc, sw, sj, sk = stroke; scol, sa = argb(sc)
            attrs += f' stroke="{scol}" stroke-opacity="{sa:.3f}" stroke-width="{sw}" stroke-linejoin="{sj}" stroke-linecap="{sk}"'
        t = extra.get("t")
        if t: attrs = f'transform="translate({t[0]},{t[1]})" ' + attrs
        self.body.append(f"<path {attrs}/>")

def svg_icon(bg, fg, size=512, clip=None, scale=1.0):
    s = Svg()
    for l in bg: s.layer(*l)
    bg_body, s.body = s.body, []
    for l in fg: s.layer(*l)
    fg_body = f'<g transform="translate({FG_PIVOT[0]},{FG_PIVOT[1]}) scale({scale}) translate({-FG_PIVOT[0]},{-FG_PIVOT[1]})">{"".join(s.body)}</g>'
    s.body = bg_body + [fg_body]
    cdef = {"circle": '<clipPath id="r"><circle cx="54" cy="54" r="54"/></clipPath>',
            "squircle": '<clipPath id="r"><rect width="108" height="108" rx="30"/></clipPath>'}.get(clip, "")
    g = '<g clip-path="url(#r)">' if clip else "<g>"
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="{size}" height="{size}">'
            f'<defs>{cdef}{"".join(s.defs)}</defs>{g}{"".join(s.body)}</g></svg>\n')

(ROOT / "design/logo.svg").write_text(svg_icon(BG_LAYERS, FG_LAYERS, clip="squircle", scale=1.0))
subprocess.run(["rsvg-convert", "-w", "512", "-h", "512", str(ROOT / "design/logo.svg"), "-o", str(ROOT / "design/icon-512.png")], check=True)

# ── Android vector drawable ──
def gradient_xml(fill, indent):
    if fill[0] == "lin":
        _, x0, y0, x1, y1, st = fill
        head = f'android:startX="{x0}" android:startY="{y0}" android:endX="{x1}" android:endY="{y1}"'
    else:
        _, cx, cy, r, st = fill
        head = f'android:type="radial" android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{r}"'
    items = "".join(f'{indent}            <item android:offset="{o}" android:color="{c}"/>\n' for o, c in st)
    return f'{indent}    <aapt:attr name="android:fillColor">\n{indent}        <gradient {head}>\n{items}{indent}        </gradient>\n{indent}    </aapt:attr>\n'

def path_xml(d, fill, stroke, extra, indent="    "):
    attrs = [f'android:pathData="{d}"']
    inner = ""
    if isinstance(fill, str): attrs.insert(0, f'android:fillColor="{fill}"')
    else: inner = gradient_xml(fill, indent)
    if stroke:
        sc, sw, sj, sk = stroke
        attrs += [f'android:strokeColor="{sc}"', f'android:strokeWidth="{sw}"', f'android:strokeLineJoin="{sj}"', f'android:strokeLineCap="{sk}"']
    body = f'{indent}<path\n' + "".join(f'{indent}    {a}\n' for a in attrs).rstrip("\n") + (">\n" + inner + f"{indent}</path>\n" if inner else "/>\n")
    t = extra.get("t")
    if t:
        inner_lines = "".join("    " + ln + "\n" for ln in body.rstrip("\n").split("\n"))
        body = f'{indent}<group android:translateX="{t[0]}" android:translateY="{t[1]}">\n{inner_lines}{indent}</group>\n'
    return body

HEAD = '<?xml version="1.0" encoding="utf-8"?>\n'
def vec(w, h, vw, vh, body):
    return (HEAD + f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{w}dp"\n    android:height="{h}dp"\n    android:viewportWidth="{vw}"\n    android:viewportHeight="{vh}">\n{body}</vector>\n')

(RES / "drawable/ic_launcher_background.xml").write_text(vec(108, 108, 108, 108, "".join(path_xml(*l) for l in BG_LAYERS)))
def scaled_group(inner):
    return (f'    <group android:scaleX="{FG_SCALE}" android:scaleY="{FG_SCALE}" '
            f'android:pivotX="{FG_PIVOT[0]}" android:pivotY="{FG_PIVOT[1]}">\n{inner}    </group>\n')
(RES / "drawable/ic_launcher_foreground.xml").write_text(vec(108, 108, 108, 108, scaled_group("".join(path_xml(*l, indent="        ") for l in FG_LAYERS))))

def silhouette(color):
    return f'    <path\n        android:fillColor="{color}"\n        android:fillType="evenOdd"\n        android:pathData="{SILHOUETTE}"/>\n'
(RES / "drawable/ic_launcher_monochrome.xml").write_text(vec(108, 108, 108, 108, scaled_group(silhouette("#FFFFFF").replace("\n    ", "\n        ").replace("    <path", "        <path", 1))))
# Notificación: viewport recortado alrededor de la casa (≈ 24dp de lado).
(RES / "drawable/ic_notification.xml").write_text(vec(24, 24, 70, 70, f'    <group android:translateX="-19" android:translateY="-15">\n{silhouette("#FFFFFF")}    </group>\n'))
# Pantalla de entrada: el glifo sin fondo, un poco más grande que en el ícono (no hay zona segura que respetar).
logo = '    <group android:scaleX="1.18" android:scaleY="1.18" android:pivotX="54" android:pivotY="54">\n' + \
       "".join(path_xml(*l, indent="        ") for l in LOGO_LAYERS) + "    </group>\n"
(RES / "drawable/ic_logo.xml").write_text(vec(96, 96, 108, 108, logo))
(RES / "mipmap-anydpi-v26/ic_launcher.xml").write_text(HEAD + '''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
''')

# ── Vista previa con la máscara REAL del launcher (diámetro 72 de 108), a escala 1.5 para verla bien ──
defs, parts = [], []
def placed(tx, shape, bg, fg, scale):
    s = Svg()
    for l in bg: s.layer(*l)
    bgb, s.body = s.body, []
    for l in fg: s.layer(*l)
    inner = f'<g transform="translate({FG_PIVOT[0]},{FG_PIVOT[1]}) scale({scale}) translate({-FG_PIVOT[0]},{-FG_PIVOT[1]})">{"".join(s.body)}</g>'
    defs.extend(s.defs)
    return f'<g transform="translate({tx},0) scale(1.5) translate(-18,-18)"><g clip-path="url(#{shape})">{"".join(bgb)}{inner}</g></g>'
defs.append('<clipPath id="pc"><circle cx="54" cy="54" r="36"/></clipPath><clipPath id="pq"><rect x="18" y="18" width="72" height="72" rx="22"/></clipPath>')
parts.append(placed(10, "pc", BG_LAYERS, FG_LAYERS, FG_SCALE))
parts.append(placed(130, "pq", BG_LAYERS, FG_LAYERS, FG_SCALE))
parts.append(f'<g transform="translate(250,0) scale(1.5) translate(-18,-18)"><g clip-path="url(#pc)"><rect width="108" height="108" fill="#2b2b36"/><g transform="translate({FG_PIVOT[0]},{FG_PIVOT[1]}) scale({FG_SCALE}) translate({-FG_PIVOT[0]},{-FG_PIVOT[1]})"><path d="{SILHOUETTE}" fill="#e8d9ff" fill-rule="evenodd"/></g></g></g>')
s = Svg()
for l in LOGO_LAYERS: s.layer(*l)
defs.extend(s.defs)
parts.append(f'<g transform="translate(370,6)"><rect width="108" height="108" rx="12" fill="#F7F5FB"/>{"".join(s.body)}</g>')
prev = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 490 116" width="1470" height="348"><defs>{"".join(defs)}</defs><rect width="490" height="116" fill="#e4e4ea"/>{"".join(parts)}</svg>'
pathlib.Path("/tmp/toka-logo-preview.svg").write_text(prev)
subprocess.run(["rsvg-convert", "/tmp/toka-logo-preview.svg", "-o", "/tmp/toka-logo-preview.png"], check=True)
print("ok")
