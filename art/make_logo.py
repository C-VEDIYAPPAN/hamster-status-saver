"""Hamster logo (run from art/): built from simple shapes so the same geometry exports to SVG (preview/store icon)
and Android VectorDrawable (launcher icon). Viewport 108x108 (adaptive-icon foreground)."""
import sys

def ellipse_path(cx, cy, rx, ry):
    # Two arcs make a full ellipse (VectorDrawable has no <ellipse>)
    return (f"M{cx-rx:.2f},{cy:.2f} A{rx:.2f},{ry:.2f} 0 1,0 {cx+rx:.2f},{cy:.2f} "
            f"A{rx:.2f},{ry:.2f} 0 1,0 {cx-rx:.2f},{cy:.2f} Z")

FUR      = "#F2A541"   # warm golden fur
FUR_DARK = "#D9822B"   # ear rims / forehead stripe
CREAM    = "#FFF1D6"   # cheeks, muzzle, belly
PINK     = "#F59AA8"   # inner ears, nose
EYE      = "#2B1D14"
WHITE    = "#FFFFFF"
TEAL     = "#0E7C7B"

# (path, fill, opacity) in drawing order
shapes = [
    # ears
    (ellipse_path(36, 30.5, 8.6, 8.6), FUR_DARK, 1),
    (ellipse_path(72, 30.5, 8.6, 8.6), FUR_DARK, 1),
    (ellipse_path(36, 31, 4.9, 4.9), PINK, 1),
    (ellipse_path(72, 31, 4.9, 4.9), PINK, 1),
    # body (peeks out under the head)
    (ellipse_path(54, 74, 19, 13), FUR, 1),
    (ellipse_path(54, 77, 12, 9), CREAM, 1),
    # head
    (ellipse_path(54, 51.5, 25, 22), FUR, 1),
    # forehead stripe
    ("M54,31 C51,31 50,35 51,40 C52,43 56,43 57,40 C58,35 57,31 54,31 Z", FUR_DARK, 0.5),
    # stuffed cheeks — the signature: bulging past the head outline
    (ellipse_path(36, 57.5, 12, 10.5), CREAM, 1),
    (ellipse_path(72, 57.5, 12, 10.5), CREAM, 1),
    # muzzle
    (ellipse_path(54, 58, 7.5, 6), CREAM, 1),
    # eyes + sparkle
    (ellipse_path(45, 47.5, 3.4, 3.7), EYE, 1),
    (ellipse_path(63, 47.5, 3.4, 3.7), EYE, 1),
    (ellipse_path(46.1, 46.2, 1.15, 1.15), WHITE, 1),
    (ellipse_path(64.1, 46.2, 1.15, 1.15), WHITE, 1),
    # nose
    ("M51.6,54.1 Q54,52.9 56.4,54.1 Q55.6,56.5 54,56.9 Q52.4,56.5 51.6,54.1 Z", PINK, 1),
    # blush
    (ellipse_path(36, 60.5, 3.3, 2.2), PINK, 0.55),
    (ellipse_path(72, 60.5, 3.3, 2.2), PINK, 0.55),
    # the save token it is hoarding: white disc, hugged by two paws
    (ellipse_path(54, 77.5, 9.6, 9.6), WHITE, 1),
    (ellipse_path(44.2, 77, 4.3, 3.7), FUR_DARK, 1),
    (ellipse_path(63.8, 77, 4.3, 3.7), FUR_DARK, 1),
    # smile (stroke)
    ("M54,56.9 L54,58.7 M54,58.7 Q52.2,60.7 50.6,59.5 M54,58.7 Q55.8,60.7 57.4,59.5", None, 1),
]
arrow = "M54,72.4 L54,81.6 M50.2,78.2 L54,82 L57.8,78.2"

def svg(size=512, with_bg=True, mono=False):
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="{size}" height="{size}">',
           '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
           '<stop offset="0" stop-color="#0B5F63"/><stop offset="1" stop-color="#19A99A"/></linearGradient>'
           '<clipPath id="c"><rect width="108" height="108" rx="24"/></clipPath></defs>',
           '<g clip-path="url(#c)">' if with_bg else '<g>']
    if with_bg:
        out.append('<rect width="108" height="108" fill="url(#g)"/>')
        out.append('<g transform="translate(54 55) scale(1.18) translate(-54 -56)">')
    for d, fill, op in shapes:
        if fill is None:
            out.append(f'<path d="{d}" fill="none" stroke="{EYE if not mono else "#fff"}" stroke-width="1.2" stroke-linecap="round"/>')
        else:
            f = "#ffffff" if mono else fill
            out.append(f'<path d="{d}" fill="{f}" fill-opacity="{op}"/>')
    out.append(f'<path d="{arrow}" fill="none" stroke="{TEAL}" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>')
    if with_bg:
        out.append('</g>')
    out.append('</g></svg>')
    return "\n".join(out)

def vector(scale=1.0, mono=False, dy=0):
    """Android VectorDrawable for the adaptive foreground (shapes scaled about the centre)."""
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Hamster logo. Original artwork for Hamster - Status Saver. (c) 2026 Vediyappan. -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
             f'    <group android:pivotX="54" android:pivotY="56" android:scaleX="{scale}" android:scaleY="{scale}" android:translateY="{dy}">']
    for d, fill, op in shapes:
        if fill is None:
            col = "#FFFFFFFF" if mono else "#FF" + EYE[1:]
            lines.append(f'        <path android:pathData="{d}" android:strokeColor="{col}" android:strokeWidth="1.2" android:strokeLineCap="round" />')
        else:
            a = format(round(op * 255), '02X')
            col = ("#" + a + "FFFFFF") if mono else ("#" + a + fill[1:])
            lines.append(f'        <path android:pathData="{d}" android:fillColor="{col}" />')
    col = "#FF000000" if mono else "#FF" + TEAL[1:]
    lines.append(f'        <path android:pathData="{arrow}" android:strokeColor="{col}" android:strokeWidth="2.6" android:strokeLineCap="round" android:strokeLineJoin="round" />')
    lines += ['    </group>', '</vector>']
    return "\n".join(lines)

if __name__ == "__main__":
    open("hamster.svg", "w").write(svg())
    import cairosvg
    cairosvg.svg2png(bytestring=svg().encode(), write_to="hamster-512.png", output_width=512, output_height=512)
    # small-size check, like a launcher icon
    cairosvg.svg2png(bytestring=svg().encode(), write_to="hamster-96.png", output_width=96, output_height=96)
    res = "../app/src/main/res/drawable/"
    open(res + "ic_launcher_foreground.xml", "w").write(vector(0.84))   # inside the 66dp safe zone
    open(res + "logo_mark.xml", "w").write(vector(1.18, dy=-1))         # in-app logo on the gradient tile
    cairosvg.svg2png(bytestring=svg().encode(), write_to="play-store-icon-512.png", output_width=512, output_height=512)
    open("logo.svg", "w").write(svg())
    print("ok")
