#!/usr/bin/env python3
"""Creates the Final Testing input files in testing/fixtures/ (synthetic, original text, no personal data).

Needs: Pillow (with raqm for Bengali shaping), img2pdf, reportlab.   python3 scripts/make-test-fixtures.py
"""
import hashlib
import io
import os
import random

import img2pdf
from PIL import Image, ImageDraw, ImageFilter, ImageFont, features
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "testing", "fixtures")
FONT = os.path.join(ROOT, "app", "src", "main", "res", "font", "hind_siliguri_regular.ttf")
assert features.check("raqm"), "Pillow needs raqm for correct Bengali shaping"
os.makedirs(OUT, exist_ok=True)
random.seed(20261127)

BN = [
    "ব্যক্তিগত জ্ঞানভান্ডার পরীক্ষা",
    "আজ আমরা মাসিক বাজেট পরিকল্পনা নিয়ে আলোচনা করব।",
    "সঞ্চয় বাড়ানোর জন্য প্রতি মাসে আয়ের একটি অংশ আলাদা রাখুন।",
    "কৃষি প্রকল্পে টাওয়ার পদ্ধতিতে সবজি চাষ করা যায়।",
    "করতে হবে: শুক্রবারের আগে প্রতিবেদন জমা দেওয়া।",
]
EN = [
    "Personal Knowledge OS - OCR test",
    "Kotlin coroutines make asynchronous code readable.",
    "Structured concurrency keeps every task inside a scope.",
    "TODO: review the backup restore checklist.",
]


def text_image(lines, size, font_size, lang, margin=60, gap=1.6):
    img = Image.new("RGB", size, "white")
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT, font_size, layout_engine=ImageFont.Layout.RAQM)
    y = margin
    width = size[0] - 2 * margin
    for ln in lines:
        words, cur = ln.split(" "), ""
        for w in words:  # wrap so no text is cut off at the edge
            nxt = (cur + " " + w).strip()
            if cur and d.textlength(nxt, font=f, language=lang) > width:
                d.text((margin, y), cur, fill="black", font=f, language=lang); y += int(font_size * gap); cur = w
            else:
                cur = nxt
        d.text((margin, y), cur, fill="black", font=f, language=lang)
        y += int(font_size * gap)
    return img


def save(name, data):
    p = os.path.join(OUT, name)
    with open(p, "wb") as fh:
        fh.write(data)
    return p


def png(img):
    b = io.BytesIO(); img.save(b, "PNG"); return b.getvalue()


def jpg(img, q=85):
    b = io.BytesIO(); img.save(b, "JPEG", quality=q); return b.getvalue()


# 1. Phone screenshot with Bengali text (OCR-01)
save("bn-screenshot.png", png(text_image(BN, (1080, 1600), 54, "bn")))

# 2. English "photo": slight rotation, blur and noise (OCR-02)
ph = text_image(EN, (1600, 900), 56, "en").rotate(2.5, expand=True, fillcolor=(235, 235, 230))
ph = ph.filter(ImageFilter.GaussianBlur(0.8))
px = ph.load()
for _ in range(25000):
    x, y = random.randrange(ph.width), random.randrange(ph.height)
    v = random.randrange(150, 255); px[x, y] = (v, v, v)
save("en-photo.jpg", jpg(ph))

# 3. Scanned (image-only) Bengali PDF, 12 pages: OCR reads the first 10 (OCR-03)
pages = []
for i in range(1, 13):
    pg = text_image([f"পৃষ্ঠা {str(i).translate(str.maketrans('0123456789', '০১২৩৪৫৬৭৮৯'))}"] + BN[1:], (1240, 1754), 44, "bn", margin=110)
    pages.append(jpg(pg.convert("L").convert("RGB"), 80))
save("scanned-bn-12pages.pdf", img2pdf.convert(pages))

# 4. Normal text PDF in English (CAP-06)
b = io.BytesIO(); c = canvas.Canvas(b, pagesize=A4)
c.setTitle("PKOS test document")
y = 800
for ln in EN + ["This PDF has real text, so no OCR is needed."]:
    c.drawString(72, y, ln); y -= 22
c.showPage(); c.save()
save("text-en.pdf", b.getvalue())

# 5. A PDF just over Notion's 5 MB free-plan limit (NOT-08 / NOT-09)
big = []
while sum(len(x) for x in big) < 5_900_000:
    noise = Image.effect_noise((1400, 1400), 80).convert("RGB")
    big.append(jpg(noise, 95))
save("big-6mb.pdf", img2pdf.convert(big))

# 6. Three small images for the multi-file share (CAP-07)
for i, col in enumerate([(220, 60, 60), (60, 160, 90), (60, 90, 200)], 1):
    im = Image.new("RGB", (800, 600), col)
    ImageDraw.Draw(im).text((40, 40), f"PKOS-TEST image {i}", fill="white", font=ImageFont.truetype(FONT, 60))
    save(f"multi-{i}.png", png(im))

# 7. Text samples
save("sensitive-sample.txt", (
    "PKOS-TEST sensitive sample (all values are FAKE test data)\n"
    "Your OTP is 482913. Do not share it.\n"
    "Card: 4111 1111 1111 1111 (public test number)\n").encode())
save("todo-sample.txt", (
    "PKOS-TEST কাজের তালিকা\n"
    "TODO: ব্যাংকে নতুন সঞ্চয় হিসাব খুলতে হবে\n"
    "করতে হবে: বাজেট শিট হালনাগাদ\n"
    "Action: send the weekly report\n").encode())

# 8. Checksums
lines = []
for n in sorted(os.listdir(OUT)):
    p = os.path.join(OUT, n)
    if n.endswith((".md", ".sha256")) or not os.path.isfile(p):
        continue
    lines.append(f"{hashlib.sha256(open(p, 'rb').read()).hexdigest()}  {n}  ({os.path.getsize(p)} bytes)")
save("SHA256SUMS.txt", ("\n".join(lines) + "\n").encode())
print("\n".join(lines))
