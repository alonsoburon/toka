#!/usr/bin/env python3
"""Genera el logo de Toka (casa + check) en todos sus formatos desde una sola descripción de capas.

Estilo "Google moderno": fondo con degradado y brillo, casa con techo en dos caras (papel doblado),
sombras suaves apiladas y una insignia con check superpuesta. Las sombras se simulan con capas
semitransparentes porque un vector de Android no tiene desenfoque.

Salidas:
  design/logo.svg, design/icon-512.png                       master y PNG de 512 px
  android/.../res/drawable/ic_launcher_background.xml        fondo (degradado + brillo)
  android/.../res/drawable/ic_launcher_foreground.xml        casa + insignia
  android/.../res/drawable/ic_launcher_monochrome.xml        silueta de un solo color (íconos temáticos, Android 13+)
  android/.../res/drawable/ic_logo.xml                       ícono completo (tile) para la pantalla de entrada
  android/.../res/drawable/ic_notification.xml               silueta blanca para la barra de estado
Uso: python3 design/make_logo.py
"""
import math, pathlib, re, subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "android/app/src/main/res"
PINK, VIOLET = "#F472B6", "#8B5CF6"

# ── Geometría (lienzo 108; zona segura del ícono adaptativo = círculo de 66 centrado) ──
ROOF_L = "M54,28.5L27.5,52H54Z"
ROOF_R = "M54,28.5L80.5,52H54Z"
BODY = "M36,52H72V75Q72,78 69,78H39Q36,78 36,75Z"
EAVE_SHADOW = "M36,52H72V59H36Z"
HOUSE_OUTLINE = "M54,28.5L80.5,52H72V78H36V52H27.5Z"
ELLIPSE = lambda cx, cy, rx, ry: f"M{cx-rx},{cy}a{rx},{ry} 0 1 1 {2*rx},0a{rx},{ry} 0 1 1 {-2*rx},0"
BADGE = lambda cx, cy, r: f"M{cx},{cy-r}a{r},{r} 0 1 1 0,{2*r}a{r},{r} 0 1 1 0,{-2*r}"
CHECK = "M60,66.6L64.1,70.7L72.2,62"

# Capas: (path, fill, stroke, extra). fill: "#AARRGGBB"/"#RRGGBB" | ("lin", x0,y0,x1,y1,c0,c1) | ("rad", cx,cy,r,c0,c1)
# stroke: (color, width, join, cap) o None. translate: (dx, dy) opcional.
BG_LAYERS = [
    ("M0,0h108v108h-108z", ("lin", 0, 0, 108, 108, PINK, VIOLET), None, {}),
    ("M0,0h108v108h-108z", ("rad", 24, 10, 78, "#66FFFFFF", "#00FFFFFF"), None, {}),   # brillo arriba a la izquierda
]
FG_LAYERS = [
    # sombra de suelo bajo la casa (elipses apiladas = falso desenfoque) y una sombra mínima desplazada
    (ELLIPSE(54, 80.5, 25, 4.6), "#0F000000", None, {}),
    (ELLIPSE(54, 80.2, 22, 3.6), "#14000000", None, {}),
    (ELLIPSE(54, 80, 18, 2.6), "#1C000000", None, {}),
    (HOUSE_OUTLINE, "#0C000000", ("#0C000000", 3, "round", "round"), {"t": (0, 2.2)}),
    (BODY, ("lin", 0, 52, 0, 78, "#FFFFFF", "#EDE3FF"), ("#FFFFFF", 1.2, "round", "round"), {}),
    (EAVE_SHADOW, ("lin", 0, 52, 0, 59, "#26301060", "#00301060"), None, {}),            # sombra del alero sobre la pared
    (ROOF_L, ("lin", 27, 28, 54, 52, "#FFFFFF", "#F4ECFF"), ("#FBF7FF", 3.2, "round", "round"), {}),
    (ROOF_R, ("lin", 54, 28, 81, 52, "#E9DCFF", "#D2BCFA"), ("#DDCBFC", 3.2, "round", "round"), {}),
    # insignia con check
    (BADGE(66, 70.5, 13.4), "#1A000000", None, {}),
    (BADGE(66, 69, 12.6), "#26000000", None, {}),
    (BADGE(66, 66, 11.8), ("lin", 55, 54, 77, 78, "#6EE7B7", "#059669"), ("#80FFFFFF", 1.1, "round", "round"), {}),
    (CHECK, "#00000000", ("#FFFFFF", 3.9, "round", "round"), {}),
]

# ── Silueta de un solo color (monocromo y notificación): casa con el check como agujero ──
HOUSE_POLY = [(54, 28), (81, 52), (72.5, 52), (72.5, 78), (35.5, 78), (35.5, 52), (27, 52)]
CHECK_PTS = [(41.5, 58.5), (50, 67), (66, 51)]
JOIN, CHECK_EFFECTIVE_W = 5.0, 6.5

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
SILHOUETTE = poly(HOUSE_POLY) + poly(check_polygon(CHECK_PTS, CHECK_EFFECTIVE_W + JOIN))

# ── Emisión: SVG ──
def argb(c):
    """'#AARRGGBB' | '#RRGGBB' -> ('#rrggbb', alpha 0..1)"""
    c = c.lstrip("#")
    if len(c) == 8: return "#" + c[2:], int(c[:2], 16) / 255
    return "#" + c, 1.0

class Svg:
    def __init__(self): self.defs, self.body, self.n = [], [], 0
    def paint(self, fill):
        if isinstance(fill, str):
            col, a = argb(fill); return col, a
        self.n += 1; gid = f"g{self.n}"
        if fill[0] == "lin":
            _, x0, y0, x1, y1, c0, c1 = fill
            (k0, a0), (k1, a1) = argb(c0), argb(c1)
            self.defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x0}" y1="{y0}" x2="{x1}" y2="{y1}">'
                             f'<stop offset="0" stop-color="{k0}" stop-opacity="{a0:.3f}"/><stop offset="1" stop-color="{k1}" stop-opacity="{a1:.3f}"/></linearGradient>')
        else:
            _, cx, cy, r, c0, c1 = fill
            (k0, a0), (k1, a1) = argb(c0), argb(c1)
            self.defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">'
                             f'<stop offset="0" stop-color="{k0}" stop-opacity="{a0:.3f}"/><stop offset="1" stop-color="{k1}" stop-opacity="{a1:.3f}"/></radialGradient>')
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

def svg_icon(layers_bg, layers_fg, rounded=True, size=512):
    s = Svg()
    for l in layers_bg + layers_fg: s.layer(*l)
    clip = '<clipPath id="r"><rect width="108" height="108" rx="26"/></clipPath>' if rounded else ""
    g = '<g clip-path="url(#r)">' if rounded else "<g>"
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="{size}" height="{size}">'
            f'<defs>{clip}{"".join(s.defs)}</defs>{g}{"".join(s.body)}</g></svg>\n')

(ROOT / "design/logo.svg").write_text(svg_icon(BG_LAYERS, FG_LAYERS))
subprocess.run(["rsvg-convert", "-w", "512", "-h", "512", str(ROOT / "design/logo.svg"), "-o", str(ROOT / "design/icon-512.png")], check=True)

# ── Emisión: vector drawable de Android ──
def xml_color(c):  # ya viene en #AARRGGBB o #RRGGBB
    return c
def path_xml(d, fill, stroke, extra, indent="    "):
    attrs = [f'android:pathData="{d}"']
    inner = ""
    if isinstance(fill, str):
        attrs.insert(0, f'android:fillColor="{fill}"')
    else:
        if fill[0] == "lin":
            _, x0, y0, x1, y1, c0, c1 = fill
            g = f'android:startX="{x0}" android:startY="{y0}" android:endX="{x1}" android:endY="{y1}" android:startColor="{c0}" android:endColor="{c1}"'
        else:
            _, cx, cy, r, c0, c1 = fill
            g = f'android:type="radial" android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{r}" android:startColor="{c0}" android:endColor="{c1}"'
        inner = f'{indent}    <aapt:attr name="android:fillColor">\n{indent}        <gradient {g}/>\n{indent}    </aapt:attr>\n'
    if stroke:
        sc, sw, sj, sk = stroke
        attrs += [f'android:strokeColor="{sc}"', f'android:strokeWidth="{sw}"', f'android:strokeLineJoin="{sj}"', f'android:strokeLineCap="{sk}"']
    body = f'{indent}<path\n' + "".join(f'{indent}    {a}\n' for a in attrs).rstrip("\n") + (">\n" + inner + f"{indent}</path>\n" if inner else "/>\n")
    t = extra.get("t")
    if t:
        body = f'{indent}<group android:translateX="{t[0]}" android:translateY="{t[1]}">\n' + body.replace("\n" + indent, "\n" + indent + "    ").replace(indent + "<path", indent + "    <path", 1) + f"{indent}</group>\n"
    return body

HEAD = '<?xml version="1.0" encoding="utf-8"?>\n'
def vec(w, h, vw, vh, body):
    return (HEAD + f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{w}dp"\n    android:height="{h}dp"\n    android:viewportWidth="{vw}"\n    android:viewportHeight="{vh}">\n{body}</vector>\n')

(RES / "drawable/ic_launcher_background.xml").write_text(vec(108, 108, 108, 108, "".join(path_xml(*l) for l in BG_LAYERS)))
(RES / "drawable/ic_launcher_foreground.xml").write_text(vec(108, 108, 108, 108, "".join(path_xml(*l) for l in FG_LAYERS)))

def silhouette(color):
    return (f'    <path\n        android:fillColor="{color}"\n        android:fillType="evenOdd"\n        android:strokeColor="{color}"\n'
            f'        android:strokeWidth="{JOIN}"\n        android:strokeLineJoin="round"\n        android:pathData="{SILHOUETTE}"/>\n')
(RES / "drawable/ic_launcher_monochrome.xml").write_text(vec(108, 108, 108, 108, silhouette("#FFFFFF")))
(RES / "drawable/ic_notification.xml").write_text(vec(24, 24, 56, 56, f'    <group android:translateX="-26" android:translateY="-26">\n{silhouette("#FFFFFF")}    </group>\n'))

# Ícono completo (con su tile redondeado) para la pantalla de entrada.
tile_clip = '    <group>\n        <clip-path android:pathData="M26,0H82Q108,0 108,26V82Q108,108 82,108H26Q0,108 0,82V26Q0,0 26,0Z"/>\n'
bg_xml = "".join(path_xml(*l, indent="        ") for l in BG_LAYERS)
# Dentro de la app no hace falta la zona segura del launcher: la casa se amplía para llenar mejor la baldosa.
fg_xml = "".join(path_xml(*l, indent="            ") for l in FG_LAYERS)
fg_group = '        <group android:scaleX="1.22" android:scaleY="1.22" android:pivotX="54" android:pivotY="56">\n' + fg_xml + "        </group>\n"
(RES / "drawable/ic_logo.xml").write_text(vec(96, 96, 108, 108, tile_clip + bg_xml + fg_group + "    </group>\n"))

# Ícono adaptativo con la capa monocromática (íconos temáticos, Android 13+).
(RES / "mipmap-anydpi-v26/ic_launcher.xml").write_text(HEAD + '''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
''')

# Vista previa: círculo, squircle y monocromo (tema oscuro).
def clipped(shape, tx, bg_extra=""):
    s = Svg()
    for l in BG_LAYERS + FG_LAYERS: s.layer(*l)
    return s, f'<g transform="translate({tx},11)" clip-path="url(#{shape})">{"".join(s.body)}</g>'
parts, defs = [], []
for shape, tx in (("pc", 10), ("pq", 130)):
    s, g = clipped(shape, tx); defs += s.defs; parts.append(g)
defs.append('<clipPath id="pc"><circle cx="54" cy="54" r="54"/></clipPath><clipPath id="pq"><rect width="108" height="108" rx="30"/></clipPath>')
mono = f'<g transform="translate(250,11)" clip-path="url(#pc)"><rect width="108" height="108" fill="#2b2b36"/><path d="{SILHOUETTE}" fill="#e8d9ff" fill-rule="evenodd" stroke="#e8d9ff" stroke-width="{JOIN}" stroke-linejoin="round"/></g>'
prev = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 370 130" width="1110" height="390"><defs>{"".join(defs)}</defs><rect width="370" height="130" fill="#f2f2f5"/>{"".join(parts)}{mono}</svg>'
pathlib.Path("/tmp/toka-logo-preview.svg").write_text(prev)
subprocess.run(["rsvg-convert", "/tmp/toka-logo-preview.svg", "-o", "/tmp/toka-logo-preview.png"], check=True)
print("ok")
