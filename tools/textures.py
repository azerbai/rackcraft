"""Procedural 16x16 pixel-art textures for Rackcraft.

Every texture is deterministic: noise is seeded from the content id, so regenerating
assets never produces a diff unless content.json or this file changes.
"""

import math
import random
import struct
import zlib

SIZE = 16

LED_GREEN = (96, 236, 128)
LED_AMBER = (246, 192, 72)
LED_RED = (236, 72, 58)
LED_BLUE = (92, 186, 255)
LED_OFF = (46, 54, 52)
GLASS_DARK = (18, 26, 34)
STEEL = (150, 160, 166)
GOLD = (226, 184, 72)
COPPER = (200, 120, 70)
BLACK = (24, 24, 28)


# ---------------------------------------------------------------- colour helpers

def rgb(hex_color):
    return tuple(bytes.fromhex(hex_color))


def mix(a, b, t):
    return tuple(max(0, min(255, round(x + (y - x) * t))) for x, y in zip(a, b))


def lighten(color, t):
    return mix(color, (255, 255, 255), t)


def darken(color, t):
    return mix(color, (0, 0, 0), t)


def rng_for(key):
    return random.Random(zlib.crc32(key.encode("utf-8")))


# ---------------------------------------------------------------- canvas

class Canvas:
    def __init__(self, fill=None):
        self.px = [[fill] * SIZE for _ in range(SIZE)]

    def copy(self):
        clone = Canvas()
        clone.px = [row[:] for row in self.px]
        return clone

    def get(self, x, y):
        return self.px[y][x] if 0 <= x < SIZE and 0 <= y < SIZE else None

    def set(self, x, y, color):
        if 0 <= x < SIZE and 0 <= y < SIZE:
            self.px[y][x] = color

    def rect(self, x0, y0, x1, y1, color):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, color)

    def hline(self, x0, x1, y, color):
        self.rect(x0, y, x1, y, color)

    def vline(self, x, y0, y1, color):
        self.rect(x, y0, x, y1, color)

    def frame(self, x0, y0, x1, y1, color):
        self.hline(x0, x1, y0, color)
        self.hline(x0, x1, y1, color)
        self.vline(x0, y0, y1, color)
        self.vline(x1, y0, y1, color)

    def bevel(self, x0, y0, x1, y1, light, dark):
        self.hline(x0, x1, y0, light)
        self.vline(x0, y0, y1, light)
        self.hline(x0 + 1, x1, y1, dark)
        self.vline(x1, y0 + 1, y1, dark)

    def inset(self, x0, y0, x1, y1, fill, light, dark):
        """A recessed panel: shadow on top/left, highlight on bottom/right."""
        self.rect(x0, y0, x1, y1, fill)
        self.bevel(x0, y0, x1, y1, dark, light)

    def poly(self, points, color):
        for y in range(SIZE):
            for x in range(SIZE):
                if _inside(points, x + 0.5, y + 0.5):
                    self.set(x, y, color)

    def disc(self, cx, cy, radius, color, inner=-1.0):
        for y in range(SIZE):
            for x in range(SIZE):
                distance = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if inner < distance <= radius:
                    self.set(x, y, color)

    def line(self, x0, y0, x1, y1, color):
        steps = max(abs(x1 - x0), abs(y1 - y0), 1)
        for step in range(steps + 1):
            self.set(round(x0 + (x1 - x0) * step / steps), round(y0 + (y1 - y0) * step / steps), color)


def _inside(points, x, y):
    result = False
    j = len(points) - 1
    for i, (xi, yi) in enumerate(points):
        xj, yj = points[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            result = not result
        j = i
    return result


def png_bytes(frames):
    """Encode one or more 16x16 canvases as a vertical strip (Minecraft animation layout)."""
    if isinstance(frames, Canvas):
        frames = [frames]

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    rows = []
    for frame in frames:
        for row in frame.px:
            rows.append(b"\0" + bytes(channel for pixel in row
                                      for channel in ((0, 0, 0, 0) if pixel is None else (*pixel[:3], pixel[3] if len(pixel) > 3 else 255))))
    header = struct.pack(">2I5B", SIZE, SIZE * len(frames), 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")


# ---------------------------------------------------------------- surfaces

def noisy(base, key, amount=0.07, streak=False):
    """Fill a canvas with base colour plus deterministic per-pixel variation."""
    rng = rng_for(key)
    canvas = Canvas()
    row_bias = [rng.uniform(-amount, amount) * 0.6 for _ in range(SIZE)]
    for y in range(SIZE):
        for x in range(SIZE):
            delta = rng.uniform(-amount, amount) * (0.4 if streak else 1) + (row_bias[y] if streak else 0)
            canvas.set(x, y, lighten(base, delta) if delta > 0 else darken(base, -delta))
    return canvas


def plate(base, key, rivets=False):
    """Painted sheet-metal shell: soft top-to-bottom falloff, a crisp 1px edge, no crate bevels."""
    rng = rng_for(key + ":shell")
    canvas = Canvas()
    for y in range(SIZE):
        falloff = 0.06 - y * 0.012
        for x in range(SIZE):
            delta = falloff + rng.uniform(-0.025, 0.025)
            canvas.set(x, y, lighten(base, delta) if delta > 0 else darken(base, -delta))
    edge = darken(base, 0.45)
    canvas.frame(0, 0, 15, 15, edge)
    canvas.hline(1, 14, 1, lighten(base, 0.14))
    if rivets:
        for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
            canvas.set(x, y, lighten(base, 0.3))
    return canvas


# ---------------------------------------------------------------- machine sides, tops and backs

def side_panel(base, key):
    canvas = plate(base, key + ":side")
    canvas.hline(1, 14, 10, darken(base, 0.28))
    canvas.hline(1, 14, 11, lighten(base, 0.08))
    for x in range(3, 13, 2):
        canvas.vline(x, 12, 13, darken(base, 0.4))
    return canvas


def side_mesh(base, key, hot=False):
    """Perforated steel door between two rails, as on rack sides and network gear."""
    shell = darken(base, 0.25)
    canvas = plate(shell, key + ":mesh")
    hole = (255, 120, 40) if hot else darken(base, 0.7)
    for y in range(2, 14):
        for x in range(3, 13):
            if (x + (y % 2)) % 2 == 0:
                canvas.set(x, y, mix(hole, darken(base, 0.7), 0.5) if hot and y > 9 else hole)
    for x in (1, 2, 13, 14):
        canvas.vline(x, 1, 14, mix(shell, STEEL, 0.35 if x in (1, 14) else 0.15))
    canvas.set(13, 7, lighten(STEEL, 0.3))
    canvas.set(13, 8, lighten(STEEL, 0.3))
    return canvas


def side_louver(base, key):
    canvas = plate(base, key + ":louver")
    for y in range(2, 14, 3):
        canvas.hline(2, 13, y, lighten(base, 0.22))
        canvas.hline(2, 13, y + 1, darken(base, 0.5))
        canvas.hline(2, 13, y + 2, darken(base, 0.25))
    return canvas


def side_genset(base, key):
    canvas = plate(base, key + ":genset")
    canvas.vline(8, 1, 14, darken(base, 0.35))
    canvas.rect(9, 7, 10, 8, darken(STEEL, 0.25))
    for y in range(3, 8, 2):
        canvas.hline(2, 6, y, darken(base, 0.55))
        canvas.hline(2, 6, y + 1, lighten(base, 0.12))
    for y in range(3, 7, 2):
        canvas.hline(10, 13, y, darken(base, 0.55))
    canvas.poly([(4, 10), (6.5, 14), (1.5, 14)], (240, 200, 40))
    canvas.vline(4, 11, 12, BLACK)
    canvas.set(4, 13, BLACK)
    return canvas


def side_cells(base, key):
    canvas = plate(darken(base, 0.2), key + ":cells")
    for x0 in (2, 9):
        canvas.rect(x0, 3, x0 + 4, 13, base)
        canvas.vline(x0, 3, 13, lighten(base, 0.25))
        canvas.vline(x0 + 4, 3, 13, darken(base, 0.3))
        canvas.set(x0 + 1, 2, LED_RED)
        canvas.set(x0 + 3, 2, BLACK)
        canvas.hline(x0 + 1, x0 + 3, 7, darken(base, 0.35))
        canvas.hline(x0 + 1, x0 + 3, 11, (240, 240, 230))
    return canvas


def side_hazard(base, key):
    canvas = plate(base, key + ":hazard")
    for x in range(1, 15):
        for y in (1, 2):
            canvas.set(x, y, (232, 196, 40) if (x + y) % 4 < 2 else BLACK)
    canvas.poly([(8, 5), (12.5, 13), (3.5, 13)], (240, 200, 40))
    canvas.poly([(8, 6.5), (11.2, 12.2), (4.8, 12.2)], BLACK)
    for x, y in ((8, 8), (7, 9), (8, 9), (9, 10), (8, 11)):
        canvas.set(x, y, (240, 200, 40))
    return canvas


def side_pipes(base, key):
    canvas = plate(base, key + ":pipes")
    pipe = (74, 160, 186)
    for y in (4, 11):
        canvas.rect(1, y - 1, 14, y + 1, pipe)
        canvas.hline(1, 14, y - 1, lighten(pipe, 0.3))
        canvas.hline(1, 14, y + 1, darken(pipe, 0.4))
        for x in (4, 11):
            canvas.vline(x, y - 2, y + 2, STEEL)
    canvas.rect(6, 6, 9, 9, darken(base, 0.3))
    canvas.set(7, 7, LED_BLUE)
    return canvas


def side_tank(base, key):
    """A vertical cylinder seen side-on: shading across columns, steel bands."""
    canvas = Canvas()
    for x in range(SIZE):
        shade = 0.28 - abs(x - 5) * 0.075
        for y in range(SIZE):
            canvas.set(x, y, lighten(base, shade) if shade > 0 else darken(base, -shade))
    for y in (2, 13):
        for x in range(SIZE):
            canvas.set(x, y, mix(canvas.get(x, y), STEEL, 0.6))
    canvas.frame(0, 0, 15, 15, darken(base, 0.5))
    canvas.rect(6, 6, 9, 9, (240, 240, 236))
    canvas.hline(6, 9, 7, LED_RED)
    canvas.vline(7, 6, 9, LED_RED)
    return canvas


def side_nacelle(base, key):
    canvas = plate(base, key + ":nacelle")
    canvas.hline(1, 14, 6, darken(base, 0.18))
    for x in range(10, 14):
        canvas.vline(x, 9, 12, darken(base, 0.3) if x % 2 else base)
    canvas.rect(2, 9, 5, 12, darken(base, 0.12))
    canvas.set(3, 10, LED_RED)
    return canvas


def side_bezel(base, key):
    canvas = plate(darken(base, 0.35), key + ":bezel")
    canvas.rect(2, 2, 13, 13, darken(base, 0.5))
    for y in range(4, 13, 3):
        canvas.hline(4, 11, y, darken(base, 0.62))
    return canvas


def side_frame(base, key):
    frame = lighten(STEEL, 0.05)
    canvas = plate(darken(STEEL, 0.35), key + ":frame")
    canvas.rect(1, 1, 14, 3, frame)
    canvas.hline(1, 14, 1, lighten(frame, 0.3))
    canvas.line(2, 14, 13, 4, frame)
    canvas.line(2, 4, 13, 14, darken(frame, 0.15))
    canvas.rect(5, 9, 10, 12, darken(base, 0.2))
    canvas.set(9, 10, LED_GREEN)
    return canvas


def side_reactor(base, key):
    canvas = plate(base, key + ":reactor")
    for y in range(1, 15):
        for x in (1, 2, 13, 14):
            canvas.set(x, y, (232, 196, 40) if (x + y) % 4 < 2 else BLACK)
    canvas.disc(8, 8, 3.6, (240, 200, 40))
    canvas.disc(8, 8, 1.0, BLACK)
    for angle in (90, 210, 330):
        for radius in (1.8, 2.4, 3.0):
            canvas.set(int(8 + math.cos(math.radians(angle)) * radius), int(8 - math.sin(math.radians(angle)) * radius), BLACK)
    return canvas


def side_creative(base, key):
    """Creative-only machines: a magenta/black hazard band like the command block's checkered sides."""
    canvas = plate(base, key + ":creative")
    for x in range(1, 15):
        for y in (12, 13, 14):
            canvas.set(x, y, (232, 80, 220) if (x + y) % 4 < 2 else BLACK)
    for x, y in ((8, 3), (7, 4), (8, 4), (9, 4), (5, 5), (6, 5), (7, 5), (8, 5), (9, 5), (10, 5), (11, 5),
                 (6, 6), (7, 6), (8, 6), (9, 6), (10, 6), (7, 7), (9, 7), (6, 8), (10, 8)):
        canvas.set(x, y, (255, 214, 250))
    return canvas


SIDE_STYLES = {
    "creative": side_creative,
    "panel": side_panel, "mesh": side_mesh, "louver": side_louver, "genset": side_genset,
    "cells": side_cells, "hazard": side_hazard, "pipes": side_pipes, "tank": side_tank,
    "nacelle": side_nacelle, "bezel": side_bezel, "frame": side_frame, "reactor": side_reactor,
}


def top_plate(base, key):
    canvas = plate(lighten(base, 0.04), key + ":top", rivets=True)
    canvas.frame(4, 4, 11, 11, darken(base, 0.22))
    return canvas


def top_mesh(base, key):
    canvas = plate(darken(base, 0.2), key + ":topmesh")
    for y in range(2, 14):
        for x in range(2, 14):
            if (x + y) % 2 == 0:
                canvas.set(x, y, darken(base, 0.7))
    canvas.rect(5, 6, 10, 9, darken(base, 0.75))
    return canvas


def top_vent(base, key):
    canvas = plate(base, key + ":topvent")
    for y in range(3, 13, 2):
        canvas.hline(3, 12, y, darken(base, 0.55))
    return canvas


def top_exhaust(base, key):
    canvas = plate(base, key + ":exhaust")
    for x in range(2, 9):
        canvas.vline(x, 2, 13, darken(base, 0.55) if x % 2 else lighten(base, 0.05))
    canvas.disc(11.5, 5, 2.6, darken(STEEL, 0.3))
    canvas.disc(11.5, 5, 1.6, (30, 28, 26))
    canvas.set(11, 4, (70, 66, 60))
    canvas.rect(10, 9, 13, 13, darken(base, 0.25))
    canvas.disc(11.5, 11, 1.2, (40, 40, 40))
    return canvas


def top_insulators(base, key):
    canvas = plate(base, key + ":insulators")
    for x in (4, 8, 12):
        canvas.disc(x, 8, 2.0, (196, 120, 80))
        canvas.disc(x, 8, 1.0, (230, 170, 130))
        canvas.set(x, 8, lighten(COPPER, 0.3))
    canvas.hline(2, 13, 12, darken(base, 0.3))
    return canvas


def top_valve(base, key):
    canvas = plate(base, key + ":valve")
    canvas.disc(8, 8, 4.5, LED_RED)
    canvas.disc(8, 8, 3.5, darken(base, 0.1))
    canvas.hline(4, 11, 8, LED_RED)
    canvas.vline(8, 4, 11, LED_RED)
    canvas.disc(8, 8, 1.2, STEEL)
    return canvas


def top_hatch(base, key):
    canvas = plate(base, key + ":hatch")
    canvas.disc(8, 8, 5.6, darken(STEEL, 0.15))
    canvas.disc(8, 8, 4.6, darken(base, 0.15))
    for angle in range(0, 360, 45):
        canvas.set(int(8 + math.cos(math.radians(angle)) * 5.1), int(8 + math.sin(math.radians(angle)) * 5.1), lighten(STEEL, 0.3))
    canvas.disc(8, 8, 2.4, (240, 200, 40))
    canvas.disc(8, 8, 0.8, BLACK)
    return canvas


def solar_top(base, key):
    canvas = Canvas(lighten(STEEL, 0.1))
    canvas.frame(0, 0, 15, 15, darken(STEEL, 0.25))
    cell = darken(base, 0.35)
    rng = rng_for(key + ":solar")
    for cy in range(2):
        for cx in range(2):
            x0, y0 = 1 + cx * 7, 1 + cy * 7
            for y in range(y0, y0 + 7):
                for x in range(x0, x0 + 7):
                    shine = 0.25 if (x - x0) + (y - y0) in (2, 3) else rng.uniform(0, 0.06)
                    canvas.set(x, y, lighten(cell, shine))
            canvas.hline(x0, x0 + 6, y0 + 3, lighten(cell, 0.18))
            canvas.vline(x0 + 3, y0, y0 + 6, lighten(cell, 0.18))
    canvas.hline(1, 14, 8, lighten(STEEL, 0.3))
    canvas.vline(8, 1, 14, lighten(STEEL, 0.3))
    return canvas


TOP_STYLES = {
    "plate": top_plate, "mesh": top_mesh, "vent": top_vent, "exhaust": top_exhaust,
    "insulators": top_insulators, "valve": top_valve, "hatch": top_hatch, "solar": solar_top,
    "fan": lambda base, key: fan_face(base, key, spinning=False)[0],
}


def back_exhaust_mesh(base, key):
    canvas = side_mesh(base, key + ":back", hot=False)
    for cx in (5, 11):
        canvas.disc(cx, 8, 2.8, darken(base, 0.75))
        canvas.disc(cx, 8, 2.8, lighten(STEEL, 0.05), inner=2.2)
        canvas.disc(cx, 8, 0.9, STEEL)
    canvas.hline(3, 12, 13, (220, 110, 50))
    return canvas


def back_radiator(base, key):
    canvas = plate(base, key + ":radiator")
    canvas.rect(2, 2, 13, 13, darken(base, 0.6))
    for x in range(2, 14):
        if x % 2 == 0:
            canvas.vline(x, 2, 13, mix(darken(base, 0.1), (230, 110, 50), 0.25))
    canvas.hline(2, 13, 2, lighten(base, 0.1))
    return canvas


def back_heat_exchanger(base, key):
    canvas = plate(base, key + ":exchanger")
    canvas.rect(2, 2, 13, 13, (60, 24, 16))
    for y in range(3, 13, 2):
        canvas.hline(2, 13, y, mix(darken(base, 0.1), (255, 110, 40), 0.45))
    for x in (2, 13):
        canvas.vline(x, 2, 13, STEEL)
    return canvas


BACK_STYLES = {"exhaust_mesh": back_exhaust_mesh, "radiator": back_radiator, "heat_exchanger": back_heat_exchanger}


def rack_face(base, key, on, alert=None):
    """Rack front. ``alert`` "warn" or "fault" swaps the status LEDs for blinking amber or red ones."""
    frames = []
    rng = rng_for(key + ":leds")
    for frame in range(4 if on or alert else 1):
        canvas = plate(base, key)
        canvas.inset(1, 1, 14, 14, darken(base, 0.55), lighten(base, 0.05), darken(base, 0.75))
        for row, y in enumerate((2, 5, 8, 11)):
            bay = lighten(base, 0.06) if row % 2 == 0 else base
            canvas.rect(2, y, 13, y + 1, bay)
            canvas.hline(2, 13, y, lighten(bay, 0.15))
            canvas.set(2, y + 1, lighten(STEEL, 0.2))
            for x in range(4, 9):
                canvas.set(x, y + 1, darken(bay, 0.35) if x % 2 else darken(bay, 0.2))
            if alert:
                led = LED_RED if alert == "fault" else LED_AMBER
                canvas.set(11, y + 1, led if frame % 2 == 0 else darken(led, 0.6))
                canvas.set(12, y + 1, led if frame % 2 == 0 else darken(led, 0.6))
            elif on:
                blink = rng.random() < 0.55 or frame % 2 == 0
                canvas.set(11, y + 1, LED_GREEN if blink else darken(LED_GREEN, 0.5))
                canvas.set(12, y + 1, LED_AMBER if rng.random() < 0.4 else darken(LED_AMBER, 0.6))
            else:
                canvas.set(11, y + 1, LED_OFF)
                canvas.set(12, y + 1, LED_OFF)
        for x in range(3, 13, 2):
            canvas.set(x, 13, darken(base, 0.6))
        if alert and frame % 2 == 0:
            # A status bar across the top that reads from across a hall.
            led = LED_RED if alert == "fault" else LED_AMBER
            canvas.hline(2, 13, 1, led)
        frames.append(canvas)
    return frames


def fan_face(base, key, phase=0.0, spinning=True, on=False):
    frames = []
    count = 4 if spinning and on else 1
    housing = plate(base, key)
    for frame in range(count):
        canvas = housing.copy()
        canvas.disc(8, 8, 6.8, darken(base, 0.7))
        rotation = phase + frame * (math.pi / 2) / count
        blade = lighten(base, 0.35)
        for y in range(SIZE):
            for x in range(SIZE):
                dx, dy = x + 0.5 - 8, y + 0.5 - 8
                distance = math.hypot(dx, dy)
                if 1.5 < distance < 6.1:
                    # Four broad blades; the leading edge is lit, the trailing edge shadowed.
                    angle = (math.atan2(dy, dx) + rotation) % (math.pi / 2)
                    if angle < 0.3:
                        canvas.set(x, y, lighten(blade, 0.25))
                    elif angle < 0.85:
                        canvas.set(x, y, blade)
                    elif angle < 1.0:
                        canvas.set(x, y, darken(blade, 0.3))
        canvas.disc(8, 8, 1.9, darken(STEEL, 0.1))
        canvas.set(7, 7, lighten(STEEL, 0.5))
        canvas.disc(8, 8, 7.3, lighten(base, 0.2), inner=6.6)
        if on:
            canvas.set(13, 2, LED_GREEN)
        frames.append(canvas)
    return frames


def grille_face(base, key, on, hot=False):
    canvas = plate(base, key)
    canvas.inset(2, 2, 13, 10, darken(base, 0.5), lighten(base, 0.1), darken(base, 0.7))
    glow = (255, 138, 46) if hot else (110, 210, 255)
    for y in range(3, 10, 2):
        canvas.hline(3, 12, y, lighten(base, 0.18))
        if on:
            canvas.hline(3, 12, y + 1, mix(darken(base, 0.5), glow, 0.55))
    canvas.inset(3, 11, 12, 13, darken(base, 0.25), lighten(base, 0.12), darken(base, 0.45))
    canvas.set(4, 12, LED_GREEN if on else LED_OFF)
    canvas.set(6, 12, LED_AMBER if on and hot else LED_OFF)
    canvas.hline(8, 11, 12, darken(STEEL, 0.2))
    canvas.set(10 if on else 8, 12, lighten(STEEL, 0.4))
    return canvas


def monitor_face(base, key, on):
    frames = []
    rng = rng_for(key + ":graph")
    samples = [rng.randint(0, 4) for _ in range(32)]
    for frame in range(8 if on else 1):
        canvas = plate(base, key)
        canvas.inset(1, 1, 14, 12, GLASS_DARK, lighten(base, 0.2), darken(base, 0.6))
        if on:
            for y in (4, 8):
                for x in range(2, 14, 2):
                    canvas.set(x, y, (28, 48, 56))
            previous = None
            for x in range(2, 14):
                value = samples[(x + frame * 2) % len(samples)]
                y = 10 - value
                canvas.set(x, y, LED_GREEN)
                if previous is not None and abs(previous - y) > 1:
                    canvas.vline(x, min(previous, y) + 1, max(previous, y) - 1, darken(LED_GREEN, 0.3))
                previous = y
            canvas.rect(2, 2, 5, 2, LED_BLUE)
            canvas.set(13, 2, LED_AMBER if frame % 4 < 2 else (60, 50, 30))
        else:
            canvas.set(3, 3, (52, 62, 74))
            canvas.set(4, 2, (52, 62, 74))
        canvas.set(3, 14, LED_GREEN if on else LED_OFF)
        canvas.hline(6, 9, 14, darken(base, 0.4))
        frames.append(canvas)
    return frames


def turbine_face(base, key, on):
    frames = []
    for frame in range(4 if on else 1):
        canvas = plate(darken(base, 0.08), key)
        canvas.disc(8, 8, 6.8, (110, 160, 204))
        canvas.disc(8, 8, 7.2, darken(base, 0.3), inner=6.8)
        rotation = frame * (2 * math.pi / 3) / 4
        for y in range(SIZE):
            for x in range(SIZE):
                dx, dy = x + 0.5 - 8, y + 0.5 - 8
                distance = math.hypot(dx, dy)
                if 1.5 < distance < 6.6:
                    angle = (math.atan2(dy, dx) - rotation) % (2 * math.pi / 3)
                    width = 0.55 - distance * 0.05
                    if angle < width:
                        canvas.set(x, y, (246, 246, 240))
                    elif angle < width + 0.2:
                        canvas.set(x, y, (170, 172, 168))
        canvas.disc(8, 8, 1.8, darken(STEEL, 0.1))
        canvas.set(7, 7, lighten(STEEL, 0.4))
        frames.append(canvas)
    return frames


def hazard_face(base, key, on):
    canvas = noisy(base, key, 0.05)
    for x in range(SIZE):
        shade = 0.25 - abs(x - 5) * 0.06
        for y in range(SIZE):
            pixel = canvas.get(x, y)
            canvas.set(x, y, lighten(pixel, shade) if shade > 0 else darken(pixel, -shade * 0.8))
    canvas.bevel(0, 0, 15, 15, lighten(base, 0.2), darken(base, 0.45))
    canvas.disc(8, 6, 3.6, (232, 232, 226))
    canvas.disc(8, 6, 4.1, darken(STEEL, 0.3), inner=3.6)
    canvas.line(8, 6, 10 if on else 6, 4, BLACK)
    canvas.set(8, 6, LED_RED)
    for x in range(1, 15):
        for y in range(12, 15):
            canvas.set(x, y, (232, 196, 40) if (x + y) % 4 < 2 else BLACK)
    return canvas


def outlets_face(base, key, on):
    canvas = plate(base, key)
    canvas.inset(3, 1, 12, 14, darken(base, 0.35), lighten(base, 0.1), darken(base, 0.55))
    for y in (2, 5, 8, 11):
        for x in (4, 9):
            canvas.rect(x, y, x + 2, y + 1, (40, 40, 44))
            canvas.set(x, y, (20, 20, 22))
            canvas.set(x + 2, y, (20, 20, 22))
    canvas.set(13, 2, LED_GREEN if on else LED_OFF)
    canvas.set(13, 4, LED_AMBER if on else LED_OFF)
    return canvas


def battery_face(base, key, on):
    frames = []
    for frame in range(5 if on else 1):
        canvas = plate(base, key)
        for column, x in enumerate((3, 7, 11)):
            canvas.inset(x - 1, 2, x + 2, 13, darken(base, 0.55), lighten(base, 0.1), darken(base, 0.7))
            canvas.rect(x, 1, x + 1, 1, lighten(STEEL, 0.2))
            level = min(5, frame + column) if on else 0
            for segment in range(5):
                y = 11 - segment * 2
                color = LED_GREEN if segment < level else darken(base, 0.4)
                canvas.hline(x, x + 1, y, color)
        frames.append(canvas)
    return frames


def meter_face(base, key, on):
    canvas = plate(base, key)
    canvas.inset(2, 2, 13, 8, (226, 222, 204), lighten(base, 0.2), darken(base, 0.5))
    for x in range(3, 13, 3):
        canvas.set(x, 3, BLACK)
    canvas.line(8, 7, 11 if on else 4, 4, LED_RED)
    canvas.set(8, 7, BLACK)
    canvas.inset(2, 10, 13, 13, darken(base, 0.3), lighten(base, 0.1), darken(base, 0.5))
    bolt = (240, 210, 60)
    for x, y in ((8, 10), (7, 11), (8, 11), (9, 11), (8, 12), (7, 13)):
        canvas.set(x, y, bolt if on else darken(bolt, 0.5))
    return canvas


def ports_face(base, key, on, core=False):
    frames = []
    rng = rng_for(key + ":ports")
    rows = (2, 6, 10) if core else (4, 9)
    for frame in range(4 if on else 1):
        canvas = plate(base, key)
        canvas.inset(1, 1, 14, 14, darken(base, 0.45), lighten(base, 0.05), darken(base, 0.65))
        for y in rows:
            for x in range(2, 14, 3):
                canvas.rect(x, y, x + 1, y + 1, (22, 22, 26))
                canvas.set(x, y, (196, 120, 180) if core else (120, 180, 210))
                if on:
                    lit = rng.random() < 0.6 or frame == 0
                    canvas.set(x + 1, y + 2 if y + 2 < 14 else y - 1, LED_GREEN if lit else darken(LED_GREEN, 0.6))
        frames.append(canvas)
    return frames


def controller_face(base, key, on):
    canvas = plate(base, key)
    canvas.inset(2, 2, 13, 7, GLASS_DARK if not on else (16, 48, 40), lighten(base, 0.2), darken(base, 0.55))
    if on:
        canvas.hline(3, 9, 3, LED_GREEN)
        canvas.hline(3, 6, 5, LED_AMBER)
        canvas.hline(8, 12, 5, LED_BLUE)
    for y in range(9, 14, 2):
        for x in range(3, 13, 3):
            canvas.rect(x, y, x + 1, y, lighten(base, 0.25))
            canvas.set(x, y + 1 if y < 13 else y, darken(base, 0.35))
    return canvas


def manifold_face(base, key, on):
    canvas = plate(base, key)
    pipe = (74, 160, 186)
    for x in (4, 11):
        canvas.rect(x - 1, 1, x + 1, 14, darken(pipe, 0.1))
        canvas.vline(x - 1, 1, 14, lighten(pipe, 0.25))
        canvas.vline(x + 1, 1, 14, darken(pipe, 0.4))
        for y in (3, 12):
            canvas.hline(x - 2, x + 2, y, STEEL)
    canvas.inset(6, 5, 9, 10, GLASS_DARK, lighten(base, 0.1), darken(base, 0.5))
    if on:
        canvas.rect(7, 7, 8, 9, LED_BLUE)
        canvas.set(7, 6, LED_GREEN)
    return canvas


def reactor_face(base, key, on):
    frames = []
    for frame in range(6 if on else 1):
        canvas = plate(base, key)
        for x in range(1, 15):
            for y in (1, 14):
                canvas.set(x, y, (232, 196, 40) if (x + y) % 4 < 2 else BLACK)
        canvas.disc(8, 8, 5.4, darken(STEEL, 0.25))
        canvas.disc(8, 8, 4.4, GLASS_DARK)
        if on:
            pulse = 0.5 + 0.5 * math.sin(frame * 2 * math.pi / 6)
            core = mix((40, 150, 210), (150, 240, 255), pulse)
            canvas.disc(8, 8, 3.4, darken(core, 0.3))
            canvas.disc(8, 8, 2.2, core)
            canvas.disc(8, 8, 1.0, lighten(core, 0.5))
        else:
            canvas.disc(8, 8, 2.2, (40, 56, 66))
        canvas.set(6, 5, (200, 220, 230))
        frames.append(canvas)
    return frames


def exchange_face(base, key, on):
    """Trading terminal: a candlestick chart over a coin badge; the chart scrolls while racks are mining."""
    frames = []
    rng = rng_for(key + ":candles")
    candles = []
    level = 7.0
    for _ in range(24):
        move = rng.uniform(-1.6, 2.0)
        candles.append((level, level + move))
        level = max(3.5, min(9.5, level + move))
    for frame in range(6 if on else 1):
        canvas = plate(base, key)
        canvas.inset(1, 1, 14, 10, GLASS_DARK, lighten(base, 0.2), darken(base, 0.6))
        if on:
            for column in range(6):
                start, end = candles[(column + frame) % len(candles)]
                x = 2 + column * 2
                up = end >= start
                top, bottom = 10 - max(start, end), 10 - min(start, end)
                colour = LED_GREEN if up else LED_RED
                canvas.vline(x, max(2, int(top) - 1), min(9, int(bottom) + 1), darken(colour, 0.45))
                canvas.vline(x, max(2, int(top)), min(9, max(int(top), int(bottom))), colour)
            canvas.set(13, 2, LED_GREEN)
        canvas.disc(8, 13, 2.4, GOLD)
        canvas.disc(8, 13, 1.4, darken(GOLD, 0.25))
        canvas.vline(8, 12, 14, lighten(GOLD, 0.4))
        canvas.set(3, 13, LED_GREEN if on else LED_OFF)
        canvas.set(12, 13, lighten(STEEL, 0.2))
        frames.append(canvas)
    return frames


def drives_face(base, key, on):
    """Eight drive bays in two columns, activity lights flickering while online."""
    frames = []
    rng = rng_for(key + ":io")
    for frame in range(4 if on else 1):
        canvas = plate(base, key)
        canvas.inset(1, 1, 14, 14, darken(base, 0.6), lighten(base, 0.05), darken(base, 0.75))
        for column, x0 in enumerate((2, 8)):
            for row in range(4):
                y0 = 2 + row * 3
                canvas.rect(x0, y0, x0 + 5, y0 + 1, darken(STEEL, 0.3))
                canvas.hline(x0, x0 + 5, y0, lighten(STEEL, 0.05))
                canvas.set(x0 + 5, y0 + 1, (LED_GREEN if rng.random() < 0.6 else darken(LED_GREEN, 0.5)) if on else LED_OFF)
                canvas.set(x0 + 4, y0 + 1, (LED_BLUE if rng.random() < 0.3 else darken(LED_BLUE, 0.6)) if on else LED_OFF)
        frames.append(canvas)
    return frames


def tapes_face(base, key, on):
    """A tape library window: a robot arm over cartridges, reels turning while online."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = plate(base, key)
        canvas.inset(1, 1, 14, 9, GLASS_DARK, lighten(base, 0.2), darken(base, 0.6))
        for x in range(2, 14, 3):
            canvas.rect(x, 6, x + 1, 8, (60, 60, 66))
            canvas.set(x, 6, (90, 140, 200))
        arm = 2 + (frame * 3 if on else 4)
        canvas.vline(arm, 2, 5, lighten(STEEL, 0.2))
        canvas.hline(2, 13, 2, darken(STEEL, 0.2))
        for cx in (5, 11):
            canvas.disc(cx, 12.5, 2.0, (110, 70, 40))
            spoke = [(0, -1), (1, 0), (0, 1), (-1, 0)][frame % 4] if on else (0, -1)
            canvas.set(cx + spoke[0], 12 + spoke[1], lighten(STEEL, 0.3))
        canvas.set(8, 12, LED_GREEN if on else LED_OFF)
        frames.append(canvas)
    return frames


def terminal_face(base, key, on):
    canvas = plate(base, key)
    canvas.inset(1, 1, 14, 11, GLASS_DARK if not on else (14, 32, 40), lighten(base, 0.2), darken(base, 0.6))
    if on:
        palette = [LED_GREEN, LED_AMBER, LED_BLUE, (200, 120, 220), (230, 230, 230)]
        rng = rng_for(key + ":icons")
        for y in (3, 6, 9):
            for x in range(3, 13, 3):
                canvas.rect(x, y, x + 1, y + 1, palette[rng.randrange(len(palette))])
    canvas.rect(3, 13, 12, 14, darken(base, 0.3))
    for x in range(4, 12, 2):
        canvas.set(x, 13, lighten(base, 0.3))
    return canvas


def antenna_face(base, key, on):
    """Transmitter front: signal bars that light up from low to high when online."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = plate(base, key)
        canvas.inset(2, 2, 13, 13, darken(base, 0.55), lighten(base, 0.1), darken(base, 0.7))
        for bar in range(4):
            x = 4 + bar * 2
            top = 11 - bar * 2 - 1
            lit = on and bar <= frame
            canvas.rect(x, top, x, 11, LED_GREEN if lit else darken(base, 0.3))
        canvas.disc(11.5, 5, 1.2, LED_RED if on and frame % 2 == 0 else darken(LED_RED, 0.6))
        frames.append(canvas)
    return frames


def top_antenna(base, key):
    canvas = plate(base, key + ":antenna")
    canvas.disc(8, 8, 4.5, darken(STEEL, 0.1))
    canvas.disc(8, 8, 3.2, lighten(STEEL, 0.15))
    canvas.disc(8, 8, 1.2, LED_RED)
    return canvas


TOP_STYLES["antenna"] = top_antenna


FRONT_STYLES = {
    "drives": lambda base, key, on: drives_face(base, key, on),
    "tapes": lambda base, key, on: tapes_face(base, key, on),
    "terminal": lambda base, key, on: [terminal_face(base, key, on)],
    "antenna": lambda base, key, on: antenna_face(base, key, on),
    "exchange": lambda base, key, on: exchange_face(base, key, on),
    "rack": lambda base, key, on: rack_face(base, key, on),
    "fan": lambda base, key, on: fan_face(base, key, on=on),
    "grille": lambda base, key, on: [grille_face(base, key, on)],
    "engine": lambda base, key, on: [grille_face(base, key, on, hot=True)],
    "monitor": lambda base, key, on: monitor_face(base, key, on),
    "turbine": lambda base, key, on: turbine_face(base, key, on),
    "hazard": lambda base, key, on: [hazard_face(base, key, on)],
    "outlets": lambda base, key, on: [outlets_face(base, key, on)],
    "battery": lambda base, key, on: battery_face(base, key, on),
    "meter": lambda base, key, on: [meter_face(base, key, on)],
    "ports": lambda base, key, on: ports_face(base, key, on),
    "core_ports": lambda base, key, on: ports_face(base, key, on, core=True),
    "controller": lambda base, key, on: [controller_face(base, key, on)],
    "manifold": lambda base, key, on: [manifold_face(base, key, on)],
    "reactor": lambda base, key, on: reactor_face(base, key, on),
    "solar": lambda base, key, on: [side_frame(base, key)],
}


def machine_textures(entry):
    """Returns {suffix: frames} for every face of a machine."""
    base = rgb(entry["color"])
    key = entry["id"]
    style = entry.get("front", "grille")
    side = SIDE_STYLES[entry.get("side", "panel")](base, key)
    back = BACK_STYLES[entry["back"]](base, key) if "back" in entry else side
    bottom = plate(darken(base, 0.35), key + ":bottom")
    faces = {
        "side": [side],
        "back": [back],
        "top": [TOP_STYLES[entry.get("top", "plate")](base, key)],
        "bottom": [bottom],
        "front": FRONT_STYLES[style](base, key, False),
        "front_on": FRONT_STYLES[style](base, key, True),
    }
    if style == "rack":
        faces["front_warn"] = rack_face(base, key, True, "warn")
        faces["front_fault"] = rack_face(base, key, True, "fault")
    if entry.get("array"):
        faces["formed"] = formed_face(base, key, False)
        faces["formed_on"] = formed_face(base, key, True)
        has_port = key not in ("battery_bank", "desalination_plant", "grid_substation", "heat_recovery_plant")
        if has_port:
            faces["port"] = port_face(base, key, False)
            faces["port_on"] = port_face(base, key, True)
        for scale, name in ((1, "large"), (2, "mega")):
            faces[f"formed_{name}"] = formed_face(base, key, False, scale)
            faces[f"formed_{name}_on"] = formed_face(base, key, True, scale)
            if has_port:
                faces[f"port_{name}"] = port_face(base, key, False, scale)
                faces[f"port_{name}_on"] = port_face(base, key, True, scale)
    return faces


YELLOW = (226, 184, 72)


def _casing(base, key):
    """The shared bones of a formed cube: a riveted, dark-framed panel. Each machine draws its own face on top."""
    canvas = plate(darken(base, 0.15), key + ":formed", rivets=True)
    canvas.frame(0, 0, 15, 15, darken(base, 0.55))
    return canvas


def _reactor_face(canvas, base, frame, on):
    """A trefoil on yellow, over a window of Cherenkov blue."""
    canvas.rect(2, 2, 13, 13, darken(base, 0.3))
    canvas.disc(8, 8, 5.6, YELLOW)
    canvas.disc(8, 8, 1.4, BLACK)
    for angle in (90, 210, 330):
        for r in (2.4, 3.2, 4.0, 4.8):
            for spread in (-22, -11, 0, 11, 22):
                a = math.radians(angle + spread)
                canvas.set(int(8 + math.cos(a) * r), int(8 - math.sin(a) * r), BLACK)
    if on:
        glow = mix((60, 140, 255), (170, 220, 255), [0.0, 0.5, 1.0, 0.5][frame])
        canvas.disc(8, 8, 1.4, glow)
        for x in (2, 13):
            canvas.vline(x, 3, 12, glow)


def _battery_face(canvas, base, frame, on):
    """Three cells with terminals and a charge gauge on each."""
    for column, x in enumerate((2, 6, 10)):
        canvas.inset(x, 3, x + 3, 13, GLASS_DARK, darken(base, 0.4), lighten(base, 0.2))
        canvas.rect(x + 1, 2, x + 2, 2, (200, 200, 205))
        level = 9 if not on else 3 + (frame + column * 2) % 7
        for y in range(12, 12 - level // 1, -1):
            if y > 3:
                canvas.hline(x + 1, x + 2, y, (96, 220, 120) if on else darken(base, 0.1))
    canvas.set(3, 1, (230, 80, 70))
    canvas.set(12, 1, (90, 160, 240))


def _mill_face(canvas, base, frame, on):
    """A toothed grinding roller turning over a tray of yellowcake dust."""
    canvas.disc(8, 7, 5.2, darken(base, 0.45))
    canvas.disc(8, 7, 4.0, lighten(base, 0.15))
    turn = frame * 15 if on else 0
    for angle in range(0, 360, 45):
        a = math.radians(angle + turn)
        canvas.set(int(round(8 + math.cos(a) * 5)), int(round(7 + math.sin(a) * 5)), lighten(base, 0.4))
    canvas.disc(8, 7, 1.5, darken(base, 0.6))
    canvas.rect(2, 12, 13, 14, (200, 170, 40))
    canvas.hline(3, 12, 12, (236, 210, 90))


def _centrifuge_face(canvas, base, frame, on):
    """Three tall rotors in a row, their bands sliding while they spin."""
    for x in (2, 6, 10):
        canvas.rect(x, 2, x + 3, 13, lighten(base, 0.25))
        canvas.vline(x, 2, 13, darken(base, 0.3))
        canvas.vline(x + 3, 2, 13, darken(base, 0.45))
        shift = frame if on else 0
        for y in range(2, 14):
            if (y + shift) % 4 == 0:
                canvas.hline(x + 1, x + 2, y, (120, 230, 200) if on else darken(base, 0.2))
        canvas.hline(x, x + 3, 1, darken(base, 0.6))
        canvas.hline(x, x + 3, 14, darken(base, 0.6))


def _fabricator_face(canvas, base, frame, on):
    """A rack of fuel rods, green-tipped, glowing faintly while it works."""
    canvas.rect(2, 2, 13, 13, GLASS_DARK)
    for column, x in enumerate(range(3, 13, 2)):
        canvas.vline(x, 4, 12, (180, 186, 196))
        tip = (110, 230, 120) if not on else mix((110, 230, 120), (210, 255, 210), ((frame + column) % 4) / 3)
        canvas.set(x, 3, tip)
        canvas.set(x, 4, tip)
    canvas.hline(2, 13, 13, darken(base, 0.4))
    canvas.hline(2, 13, 2, darken(base, 0.4))


def _sealer_face(canvas, base, frame, on):
    """A heavy round hatch with a valve wheel, inside a diagonal hazard border."""
    for y in range(16):
        for x in range(16):
            if x in (1, 14) or y in (1, 14):
                canvas.set(x, y, YELLOW if ((x + y) // 2) % 2 == 0 else BLACK)
    canvas.disc(8, 8, 5.4, darken(base, 0.4))
    canvas.disc(8, 8, 4.6, lighten(base, 0.2))
    canvas.disc(8, 8, 3.4, darken(base, 0.35), inner=2.6)
    turn = frame * 22 if on else 0
    for angle in range(0, 180, 60):
        a = math.radians(angle + turn)
        canvas.line(int(8 - math.cos(a) * 3), int(8 - math.sin(a) * 3), int(8 + math.cos(a) * 3), int(8 + math.sin(a) * 3),
                    darken(base, 0.35))
    canvas.set(8, 8, (230, 90, 70) if on else BLACK)


def _wafer_face(canvas, base, frame, on):
    """A cleanroom window: a patterned wafer under purple UV light."""
    light = mix((150, 90, 230), (210, 160, 255), [0.0, 0.5, 1.0, 0.5][frame]) if on else (70, 60, 90)
    canvas.rect(2, 2, 13, 13, darken(light, 0.55))
    canvas.disc(8, 8, 5.2, (176, 190, 206))
    for line in (5, 8, 11):
        for step in range(3, 14):
            for (x, y) in ((line, step), (step, line)):
                if math.hypot(x + 0.5 - 8, y + 0.5 - 8) <= 5.0:
                    canvas.set(x, y, (110, 124, 150))
    canvas.hline(2, 13, 2, light)
    canvas.set(6, 6, (255, 255, 255))


def _foundry_face(canvas, base, frame, on):
    """A crucible of molten silicon behind a grate."""
    canvas.rect(2, 4, 13, 13, darken(base, 0.5))
    melt = mix((240, 120, 40), (255, 220, 120), [0.0, 0.5, 1.0, 0.5][frame]) if on else (110, 70, 50)
    canvas.rect(3, 8, 12, 12, melt)
    canvas.hline(4, 11, 7, darken(melt, 0.2))
    for x in range(3, 13, 3):
        canvas.vline(x, 4, 12, (60, 60, 66))
    canvas.hline(2, 13, 4, (60, 60, 66))
    canvas.rect(5, 1, 10, 2, darken(base, 0.6))


def _recycler_face(canvas, base, frame, on):
    """Shredder teeth over a green recycling mark."""
    canvas.rect(2, 2, 13, 13, GLASS_DARK)
    shift = frame % 2 if on else 0
    for x in range(2, 14):
        canvas.set(x, 4 + ((x + shift) % 2), (190, 194, 200))
        canvas.set(x, 6 - ((x + shift) % 2), (150, 154, 160))
    green = (90, 210, 110) if on else (60, 130, 72)
    canvas.poly([(8, 7.5), (12.5, 13), (3.5, 13)], green)
    canvas.poly([(8, 9.6), (10.6, 12.3), (5.4, 12.3)], GLASS_DARK)


def _desal_face(canvas, base, frame, on):
    """Stacked membranes with sea water running through them, and a salt tray at the bottom."""
    canvas.rect(2, 2, 13, 11, (24, 52, 78))
    shift = frame if on else 0
    for row, y in enumerate((3, 6, 9)):
        for x in range(2, 14):
            wave = int(round(math.sin((x + shift * 2 + row) * 0.9)))
            canvas.set(x, y + wave, (90, 170, 230) if on else (60, 110, 150))
    canvas.rect(2, 12, 13, 13, (226, 226, 220))
    canvas.set(4, 12, (200, 200, 196))
    canvas.set(10, 13, (200, 200, 196))


def _substation_face(canvas, base, frame, on):
    """A transformer between two insulator stacks, with a bolt that flickers while it exports."""
    canvas.rect(4, 4, 11, 13, darken(base, 0.35))
    for y in range(5, 13, 2):
        canvas.hline(5, 10, y, lighten(base, 0.15))
    for x in (2, 13):
        for y in range(2, 9, 2):
            canvas.set(x, y, (200, 200, 210))
            canvas.set(x, y + 1, (120, 90, 60))
    bolt = (255, 230, 90) if on and frame % 2 == 0 else (190, 160, 60) if on else darken(base, 0.1)
    canvas.line(9, 4, 7, 8, bolt)
    canvas.line(7, 8, 9, 8, bolt)
    canvas.line(9, 8, 6, 12, bolt)


def _recovery_face(canvas, base, frame, on):
    """Radiator fins glowing warm, with a little house that gets the heat."""
    for x in range(2, 14, 2):
        warm = mix((120, 60, 50), (240, 120, 60), ((x + frame) % 6) / 5) if on else darken(base, 0.2)
        canvas.vline(x, 2, 9, warm)
    canvas.poly([(8, 9), (12.5, 12.2), (3.5, 12.2)], (150, 90, 60))
    canvas.rect(5, 12, 10, 14, (190, 160, 120))
    canvas.set(7, 13, (255, 210, 120) if on else (90, 70, 50))


FORMED_FACES = {
    "desalination_plant": _desal_face,
    "grid_substation": _substation_face,
    "heat_recovery_plant": _recovery_face,
    "modular_reactor": _reactor_face,
    "battery_bank": _battery_face,
    "uranium_mill": _mill_face,
    "gas_centrifuge": _centrifuge_face,
    "fuel_fabricator": _fabricator_face,
    "cask_sealer": _sealer_face,
    "wafer_fab": _wafer_face,
    "silicon_foundry": _foundry_face,
    "ewaste_recycler": _recycler_face,
}


GOLD_TRIM = (232, 190, 80)


def _scale_trim(canvas, base, frame, on, scale, corners_only=False):
    """What sets a bigger cube apart. Scale 1 (6x6x6 to 9x9x9): a heavy steel frame with hazard-striped corner braces.
    Scale 2 (10x10x10): dark plating with a gold trim line that pulses while it runs, and glowing corner studs."""
    if scale == 1:
        if not corners_only:
            canvas.frame(0, 0, 15, 15, (40, 42, 46))
            canvas.frame(1, 1, 14, 14, (96, 100, 108))
        for cx, cy, dx, dy in ((0, 0, 1, 1), (15, 0, -1, 1), (0, 15, 1, -1), (15, 15, -1, -1)):
            for i in range(4):
                for j in range(4 - i):
                    canvas.set(cx + dx * i, cy + dy * j, YELLOW if (i + j) % 2 == 0 else BLACK)
        for x, y in ((7, 1), (8, 1), (7, 14), (8, 14), (1, 7), (1, 8), (14, 7), (14, 8)):
            canvas.set(x, y, (180, 186, 194))
    elif scale == 2:
        gold = mix(GOLD_TRIM, (255, 245, 200), [0.0, 0.5, 1.0, 0.5][frame]) if on else GOLD_TRIM
        if not corners_only:
            canvas.frame(0, 0, 15, 15, (20, 20, 24))
            canvas.frame(1, 1, 14, 14, gold)
        stud = (150, 240, 255) if on else (90, 140, 160)
        for x, y in ((1, 1), (14, 1), (1, 14), (14, 14)):
            canvas.rect(x - 1 if x > 7 else x, y - 1 if y > 7 else y, x if x > 7 else x + 1, y if y > 7 else y + 1, stud)
        for x, y in ((7, 0), (8, 0), (7, 15), (8, 15), (0, 7), (0, 8), (15, 7), (15, 8)):
            canvas.set(x, y, gold)


def formed_face(base, key, on, scale=0):
    """Casing for a formed multiblock: every machine has its own face (a reactor's trefoil, a centrifuge's rotors,
    a fab's wafer), so cubes are easy to tell apart. It animates while the cube works. Bigger cubes (scale 1 and 2)
    keep the machine's face but wear their own trim, see _scale_trim."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = _casing(darken(base, 0.25) if scale == 2 else base, key)
        FORMED_FACES.get(key, _reactor_face)(canvas, base, frame, on)
        _scale_trim(canvas, base, frame, on, scale)
        frames.append(canvas)
    return frames


PORT = (64, 214, 224)


def port_face(base, key, on, scale=0):
    """The port: the machine's own casing behind a bright cyan frame and an output hatch with an arrow, so the one
    block you empty a cube from stands out from the rest of it."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = _casing(base, key)
        FORMED_FACES.get(key, _reactor_face)(canvas, base, frame, on)
        canvas.frame(0, 0, 15, 15, PORT)
        canvas.frame(1, 1, 14, 14, darken(PORT, 0.45))
        canvas.inset(4, 4, 11, 11, (12, 22, 26), darken(PORT, 0.7), darken(PORT, 0.3))
        arrow = (210, 250, 255) if not on else mix((210, 250, 255), PORT, [0.0, 0.5, 1.0, 0.5][frame])
        canvas.rect(7, 5, 8, 7, arrow)
        canvas.hline(5, 10, 8, arrow)
        canvas.hline(6, 9, 9, arrow)
        canvas.hline(7, 8, 10, arrow)
        _scale_trim(canvas, base, frame, on, scale, corners_only=True)
        frames.append(canvas)
    return frames


# ---------------------------------------------------------------- plain blocks

def ore_block(entry):
    stone = (74, 74, 80) if entry.get("stone") == "deepslate" else (126, 126, 126)
    canvas = noisy(stone, entry["id"] + ":stone", 0.12)
    rng = rng_for(entry["id"] + ":veins")
    for y in range(SIZE):
        for x in range(SIZE):
            if rng.random() < 0.12:
                canvas.set(x, y, darken(stone, 0.2))
    base = rgb(entry["color"])
    for cx, cy in ((4, 4), (11, 3), (7, 9), (12, 12), (3, 12)):
        cluster = [(0, 0), (1, 0), (0, 1), (1, 1)] + rng.sample([(-1, 0), (2, 1), (0, 2), (1, -1), (2, 0)], 2)
        for dx, dy in cluster:
            canvas.set(cx + dx, cy + dy, base)
        canvas.set(cx, cy, lighten(base, 0.3))
        canvas.set(cx + 1, cy + 1, darken(base, 0.3))
    return canvas


def brushed_block(entry):
    base = rgb(entry["color"])
    canvas = noisy(base, entry["id"], 0.08, streak=True)
    canvas.bevel(0, 0, 15, 15, lighten(base, 0.25), darken(base, 0.45))
    canvas.hline(1, 14, 7, darken(base, 0.25))
    canvas.hline(1, 14, 8, lighten(base, 0.12))
    canvas.vline(7, 1, 6, darken(base, 0.25))
    canvas.vline(8, 9, 14, darken(base, 0.25))
    return canvas


def floor_tile(entry):
    base = rgb(entry["color"])
    canvas = noisy(base, entry["id"], 0.04)
    canvas.bevel(0, 0, 15, 15, lighten(base, 0.25), darken(base, 0.5))
    for y in range(3, 14, 3):
        for x in range(3, 14, 3):
            canvas.set(x, y, darken(base, 0.6))
            canvas.set(x + 1, y + 1, lighten(base, 0.1))
    return canvas


def blank_panel(entry):
    base = rgb(entry["color"])
    canvas = plate(base, entry["id"], rivets=False)
    for y in range(3, 13, 2):
        canvas.hline(3, 12, y, darken(base, 0.25))
        canvas.hline(3, 12, y + 1, lighten(base, 0.08))
    for x in (1, 14):
        canvas.set(x, 3, lighten(STEEL, 0.3))
        canvas.set(x, 12, lighten(STEEL, 0.3))
    return canvas


def coal_block(entry):
    base = rgb(entry["color"])
    canvas = noisy(base, entry["id"], 0.18)
    rng = rng_for(entry["id"] + ":glint")
    for _ in range(14):
        x, y = rng.randrange(SIZE), rng.randrange(SIZE)
        canvas.set(x, y, lighten(base, 0.35))
        canvas.set(x, (y + 1) % SIZE, darken(base, 0.4))
    canvas.bevel(0, 0, 15, 15, lighten(base, 0.15), darken(base, 0.4))
    return canvas


def cable_side(entry, cut=False):
    """Side of a cable run. Rows are the radial profile (sampled around v=8); columns run along the cable."""
    base = rgb(entry["color"])
    canvas = Canvas()
    profile = {0: 0.38, 1: 0.18, 2: 0.0, 3: -0.1, 4: -0.25, 5: -0.42, 6: -0.55, 7: -0.62}
    for y in range(SIZE):
        offset = y - 7  # light catches the upper side of the cable
        shade = profile.get(abs(offset) if offset >= 0 else min(7, -offset + 1), -0.6)
        for x in range(SIZE):
            canvas.set(x, y, lighten(base, shade) if shade > 0 else darken(base, -shade))
    identifier = entry["id"]
    if identifier == "power_cable":
        for x in range(0, SIZE, 8):
            canvas.vline(x, 0, 15, darken(base, 0.55))
        for x in range(3, SIZE, 8):
            canvas.set(x, 7, (240, 230, 220))
    elif identifier == "coolant_pipe":
        for x in (0, 15):
            for y in range(SIZE):
                canvas.set(x, y, mix(canvas.get(x, y), STEEL, 0.7))
        for x in range(2, 14, 4):
            canvas.set(x, 6, lighten(base, 0.6))
    else:
        for x in range(1, SIZE, 3):
            canvas.set(x, 8, (255, 214, 250))
            canvas.set(x, 7, lighten(base, 0.5))
    if cut:
        for y in range(SIZE):
            canvas.set(7, y, BLACK)
            canvas.set(8, y, BLACK)
        for x, y in ((6, 6), (9, 9), (6, 9), (9, 6)):
            canvas.set(x, y, LED_AMBER)
        canvas.set(7, 8, COPPER)
        canvas.set(8, 7, COPPER)
    return canvas


def cable_end(entry, cut=False):
    """Cap where a cable meets a machine or another cable: a connector ring around the conductor."""
    base = rgb(entry["color"])
    canvas = Canvas(darken(base, 0.4))
    canvas.disc(8, 8, 3.2, lighten(STEEL, 0.1))
    canvas.disc(8, 8, 2.2, darken(base, 0.1))
    core = {"power_cable": COPPER, "coolant_pipe": (150, 225, 245), "fiber_cable": (255, 214, 250), "item_pipe": (240, 214, 150)}[entry["id"]]
    canvas.disc(8, 8, 1.2, LED_RED if cut else core)
    canvas.frame(4, 4, 11, 11, darken(base, 0.55))
    return canvas


def cable_joint(entry, cut=False):
    """The coupling sleeve at the centre of every cable block."""
    base = rgb(entry["color"])
    sleeve = mix(darken(base, 0.35), STEEL, 0.35)
    canvas = noisy(sleeve, entry["id"] + ":joint", 0.04)
    canvas.bevel(0, 0, 15, 15, lighten(sleeve, 0.3), darken(sleeve, 0.4))
    canvas.bevel(5, 5, 10, 10, lighten(sleeve, 0.25), darken(sleeve, 0.35))
    if cut:
        canvas.line(4, 4, 11, 11, LED_RED)
        canvas.line(11, 4, 4, 11, LED_RED)
    return canvas


def cask_block(entry):
    """A sealed dry cask: ribbed steel with a radiation trefoil."""
    base = rgb(entry["color"])
    canvas = noisy(base, entry["id"], 0.05)
    for y in range(0, SIZE, 4):
        canvas.hline(0, 15, y, darken(base, 0.25))
        canvas.hline(0, 15, y + 1, lighten(base, 0.15))
    canvas.disc(7.5, 7.5, 4.6, (226, 196, 60))
    canvas.disc(7.5, 7.5, 1.2, BLACK)
    for angle in (90, 210, 330):
        for r in (2.2, 2.9, 3.6):
            for spread in (-22, 0, 22):
                a = math.radians(angle + spread)
                canvas.set(int(7.5 + r * math.cos(a)), int(7.5 - r * math.sin(a)), BLACK)
    return canvas


BLOCK_STYLES = {
    "ore": ore_block,
    "brushed": brushed_block,
    "floor": floor_tile,
    "blank": blank_panel,
    "coal_block": coal_block,
    "cask": cask_block,
}


def block_texture(entry):
    return BLOCK_STYLES[entry["pattern"]](entry)


# ---------------------------------------------------------------- item sprites

def outline(canvas, base):
    """Add a 1px dark outline around opaque pixels, like vanilla item sprites."""
    edge = darken(base, 0.62)
    snapshot = canvas.copy()
    for y in range(SIZE):
        for x in range(SIZE):
            if snapshot.get(x, y) is not None:
                continue
            if any(snapshot.get(x + dx, y + dy) is not None for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                canvas.set(x, y, edge)
    return canvas


def item_lump(base, key, glints=True):
    canvas = Canvas()
    rng = rng_for(key)
    for y in range(SIZE):
        for x in range(SIZE):
            distance = math.hypot((x + 0.5 - 8) / 5.6, (y + 0.5 - 8.5) / 4.8)
            if distance + rng.uniform(-0.12, 0.12) < 1:
                shade = (x - y) * 0.018
                pixel = lighten(base, -shade) if shade < 0 else darken(base, shade)
                canvas.set(x, y, pixel)
    for _ in range(6):
        x, y = rng.randint(4, 11), rng.randint(5, 11)
        if canvas.get(x, y) is not None:
            canvas.set(x, y, lighten(base, 0.35) if glints else darken(base, 0.35))
    return canvas


def item_ingot(base, key):
    canvas = Canvas()
    canvas.poly([(4, 5), (14, 5), (12, 9), (2, 9)], lighten(base, 0.22))
    canvas.rect(2, 9, 11, 11, base)
    canvas.poly([(12, 9), (14, 5), (14, 8), (12, 12)], darken(base, 0.3))
    canvas.hline(4, 12, 5, lighten(base, 0.45))
    canvas.hline(2, 11, 11, darken(base, 0.2))
    canvas.set(5, 7, lighten(base, 0.55))
    canvas.set(6, 7, lighten(base, 0.4))
    return canvas


def item_crystal(base, key):
    canvas = Canvas()
    canvas.poly([(8, 1), (13, 5), (13, 11), (8, 15), (3, 11), (3, 5)], base)
    canvas.poly([(8, 1), (8, 15), (3, 11), (3, 5)], lighten(base, 0.2))
    canvas.poly([(3, 5), (8, 1), (13, 5), (8, 7)], lighten(base, 0.38))
    canvas.vline(8, 7, 14, darken(base, 0.15))
    canvas.set(6, 4, (255, 255, 255))
    canvas.set(5, 5, lighten(base, 0.7))
    return canvas


def item_orb(base, key):
    canvas = Canvas()
    for y in range(SIZE):
        for x in range(SIZE):
            distance = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if distance <= 6.2:
                t = distance / 6.2
                canvas.set(x, y, mix(lighten(base, 0.65), darken(base, 0.35), t))
    canvas.disc(8, 8, 6.4, (180, 230, 255), inner=5.6)
    canvas.disc(8, 8, 1.3, (255, 255, 255))
    canvas.set(5, 5, (255, 255, 255))
    for angle in range(0, 360, 60):
        x = 8 + math.cos(math.radians(angle)) * 4
        y = 8 + math.sin(math.radians(angle)) * 4
        canvas.set(int(x), int(y), lighten(base, 0.8))
    return canvas


def item_spool(base, key):
    canvas = Canvas()
    for y in range(SIZE):
        for x in range(SIZE):
            distance = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if 2.2 < distance <= 6.3:
                band = int((x + y) / 2) % 2
                canvas.set(x, y, lighten(base, 0.25) if band else darken(base, 0.08))
    canvas.disc(8, 8, 2.2, darken(base, 0.55))
    canvas.line(13, 10, 15, 14, base)
    return canvas


def item_circuit(base, key):
    canvas = Canvas()
    canvas.rect(1, 3, 14, 12, base)
    canvas.hline(1, 14, 3, lighten(base, 0.2))
    canvas.hline(1, 14, 12, darken(base, 0.3))
    for path in (((2, 5), (6, 5), (6, 8)), ((9, 10), (13, 10)), ((10, 4), (10, 7), (13, 7))):
        for (x0, y0), (x1, y1) in zip(path, path[1:]):
            canvas.line(x0, y0, x1, y1, COPPER)
    canvas.rect(7, 6, 9, 8, BLACK)
    canvas.set(7, 6, (70, 70, 80))
    for x, y in ((2, 10), (4, 10), (13, 5), (3, 7)):
        canvas.set(x, y, lighten(STEEL, 0.3))
    return canvas


def item_chip(base, key):
    canvas = Canvas()
    for offset in range(4, 12, 2):
        canvas.vline(offset, 2, 13, lighten(STEEL, 0.25))
        canvas.hline(2, 13, offset, lighten(STEEL, 0.25))
    canvas.rect(4, 4, 11, 11, darken(base, 0.45))
    canvas.bevel(4, 4, 11, 11, darken(base, 0.2), darken(base, 0.7))
    canvas.rect(6, 6, 9, 9, base)
    canvas.bevel(6, 6, 9, 9, lighten(base, 0.35), darken(base, 0.2))
    canvas.set(5, 5, lighten(STEEL, 0.4))
    return canvas


def item_ram(base, key):
    canvas = Canvas()
    canvas.rect(1, 4, 14, 11, base)
    canvas.hline(1, 14, 4, lighten(base, 0.2))
    for x in range(2, 14, 3):
        canvas.rect(x, 5, x + 1, 8, BLACK)
        canvas.set(x, 5, (64, 64, 72))
    for x in range(2, 14):
        canvas.set(x, 11, GOLD if x % 2 == 0 else darken(GOLD, 0.3))
    canvas.set(7, 11, None)
    return canvas


def item_coil(base, key):
    canvas = Canvas()
    canvas.rect(6, 1, 9, 14, darken(STEEL, 0.25))
    for y in range(2, 14, 2):
        canvas.line(3, y + 1, 12, y, base)
        canvas.line(3, y + 2, 12, y + 1, lighten(base, 0.35))
    canvas.rect(5, 0, 10, 1, STEEL)
    canvas.rect(5, 14, 10, 15, STEEL)
    canvas.set(4, 4, (255, 255, 255))
    return canvas


def item_board(base, key):
    canvas = Canvas()
    canvas.rect(2, 3, 13, 12, base)
    canvas.hline(2, 13, 3, lighten(base, 0.2))
    canvas.hline(2, 13, 12, darken(base, 0.3))
    canvas.rect(5, 6, 8, 9, BLACK)
    canvas.rect(11, 4, 13, 6, STEEL)
    canvas.rect(11, 8, 13, 10, STEEL)
    canvas.rect(3, 4, 4, 5, lighten(STEEL, 0.2))
    canvas.set(3, 11, LED_RED)
    canvas.set(4, 11, LED_GREEN)
    for x in range(3, 10):
        canvas.set(x, 2 if x % 2 else 3, GOLD)
    return canvas


def item_server(base, key, gpu=False, failed=False):
    canvas = Canvas()
    top, bottom = (3, 12) if gpu else (5, 10)
    canvas.rect(0, top, 15, bottom, base)
    canvas.hline(0, 15, top, lighten(base, 0.25))
    canvas.hline(0, 15, bottom, darken(base, 0.35))
    canvas.vline(0, top, bottom, lighten(STEEL, 0.1))
    canvas.vline(15, top, bottom, lighten(STEEL, 0.1))
    if gpu:
        for cx in (5, 11):
            canvas.disc(cx, 7.5, 3.2, darken(base, 0.5))
            canvas.disc(cx, 7.5, 1.1, STEEL)
            canvas.line(cx - 2, 6, cx + 2, 9, lighten(base, 0.1))
        canvas.set(1, top + 1, LED_GREEN)
    else:
        for x in range(2, 10, 2):
            canvas.vline(x, top + 2, bottom - 2, darken(base, 0.35))
        canvas.set(12, top + 2, LED_RED if failed else LED_GREEN)
        canvas.set(13, top + 2, darken(LED_AMBER, 0.5) if failed else LED_AMBER)
    if failed:
        canvas.line(3, top, 8, bottom, (34, 24, 22))
        canvas.line(8, bottom, 11, top + 1, (34, 24, 22))
        canvas.set(9, top - 1, (90, 90, 90))
        canvas.set(10, top - 2, (120, 120, 120))
    return canvas


def item_scanner(base, key):
    canvas = Canvas()
    canvas.rect(4, 1, 11, 14, base)
    canvas.vline(4, 1, 14, lighten(base, 0.25))
    canvas.vline(11, 1, 14, darken(base, 0.35))
    heat = [(40, 80, 220), (40, 200, 120), (240, 220, 60), (240, 120, 40), (220, 40, 30)]
    for y in range(2, 8):
        for x in range(5, 11):
            canvas.set(x, y, heat[min(4, (x - 5 + (7 - y)) // 2)])
    canvas.rect(5, 9, 6, 10, darken(base, 0.4))
    canvas.rect(9, 9, 10, 10, LED_RED)
    canvas.rect(6, 12, 9, 13, darken(base, 0.25))
    return canvas


def item_toolkit(base, key):
    canvas = Canvas()
    canvas.frame(5, 2, 10, 4, darken(STEEL, 0.2))
    canvas.rect(1, 5, 14, 13, base)
    canvas.hline(1, 14, 5, lighten(base, 0.25))
    canvas.hline(1, 14, 13, darken(base, 0.35))
    canvas.hline(1, 14, 8, darken(base, 0.2))
    canvas.rect(7, 7, 8, 11, (240, 240, 236))
    canvas.rect(5, 8, 10, 9, (240, 240, 236))
    canvas.set(2, 8, STEEL)
    canvas.set(13, 8, STEEL)
    return canvas


def item_cell(base, key):
    canvas = Canvas()
    canvas.rect(5, 2, 10, 13, darken(STEEL, 0.15))
    canvas.vline(5, 2, 13, lighten(STEEL, 0.3))
    canvas.vline(10, 2, 13, darken(STEEL, 0.45))
    canvas.rect(6, 1, 9, 1, STEEL)
    canvas.rect(6, 5, 9, 10, base)
    canvas.rect(7, 6, 8, 9, lighten(base, 0.55))
    canvas.vline(6, 5, 10, lighten(base, 0.2))
    canvas.rect(6, 14, 9, 14, darken(STEEL, 0.3))
    return canvas


def item_tank(base, key):
    canvas = Canvas()
    canvas.rect(4, 4, 11, 14, base)
    canvas.vline(5, 4, 14, lighten(base, 0.3))
    canvas.vline(11, 4, 14, darken(base, 0.3))
    canvas.hline(4, 11, 4, lighten(base, 0.15))
    canvas.rect(4, 8, 11, 10, LED_RED)
    canvas.vline(5, 8, 10, lighten(LED_RED, 0.3))
    canvas.rect(7, 1, 8, 3, BLACK)
    canvas.hline(8, 12, 1, BLACK)
    canvas.set(12, 2, BLACK)
    return canvas


def item_pellets(base, key):
    canvas = Canvas()
    for cx, cy in ((5, 6), (10, 5), (7, 10), (11, 10), (4, 11)):
        canvas.rect(cx - 1, cy - 2, cx + 1, cy + 2, base)
        canvas.vline(cx - 1, cy - 2, cy + 2, lighten(base, 0.25))
        canvas.vline(cx + 1, cy - 2, cy + 2, darken(base, 0.3))
        canvas.set(cx, cy - 2, lighten(base, 0.4))
    return canvas


def item_jerrycan(base, key):
    canvas = Canvas()
    canvas.rect(3, 4, 12, 14, base)
    canvas.vline(3, 4, 14, lighten(base, 0.25))
    canvas.vline(12, 4, 14, darken(base, 0.35))
    canvas.hline(3, 12, 14, darken(base, 0.35))
    canvas.frame(4, 1, 8, 3, darken(base, 0.4))
    canvas.rect(10, 2, 11, 3, darken(STEEL, 0.2))
    canvas.line(5, 6, 10, 12, darken(base, 0.2))
    canvas.line(10, 6, 5, 12, darken(base, 0.2))
    canvas.set(7, 9, lighten(base, 0.3))
    canvas.rect(5, 10, 6, 11, (110, 180, 60))
    return canvas


def item_book(base, key):
    canvas = Canvas()
    canvas.rect(2, 1, 12, 14, base)
    canvas.vline(2, 1, 14, darken(base, 0.45))
    canvas.vline(3, 1, 14, darken(base, 0.2))
    canvas.rect(13, 2, 13, 14, (236, 230, 210))
    canvas.hline(4, 13, 14, (236, 230, 210))
    canvas.hline(4, 12, 1, lighten(base, 0.25))
    canvas.inset(5, 4, 10, 9, darken(base, 0.4), lighten(base, 0.2), darken(base, 0.6))
    for y in (5, 7):
        canvas.hline(6, 9, y, (140, 150, 156))
        canvas.set(9, y, LED_GREEN)
    canvas.hline(5, 10, 11, GOLD)
    return canvas


def item_asic(base, key):
    """A 1U chassis topped with gold heat-sink fins."""
    canvas = item_server(base, key)
    for x in range(1, 15):
        canvas.vline(x, 2, 4, GOLD if x % 2 else darken(GOLD, 0.35))
    canvas.hline(1, 14, 2, lighten(GOLD, 0.35))
    return canvas


def item_multimeter(base, key):
    canvas = Canvas()
    canvas.rect(3, 1, 12, 14, base)
    canvas.vline(3, 1, 14, lighten(base, 0.3))
    canvas.vline(12, 1, 14, darken(base, 0.35))
    canvas.hline(3, 12, 14, darken(base, 0.35))
    canvas.rect(5, 2, 10, 5, (190, 210, 170))
    canvas.hline(6, 9, 3, (40, 50, 40))
    canvas.disc(7.5, 9, 2.2, (40, 40, 44))
    canvas.line(7, 9, 8, 7, lighten(STEEL, 0.4))
    canvas.set(5, 12, LED_RED)
    canvas.set(10, 12, BLACK)
    canvas.line(5, 13, 1, 15, LED_RED)
    canvas.line(10, 13, 14, 15, BLACK)
    return canvas


def item_drive(base, key):
    """A 2.5-inch drive caddy with a coloured capacity label."""
    canvas = Canvas()
    canvas.rect(2, 2, 13, 13, darken(STEEL, 0.35))
    canvas.hline(2, 13, 2, lighten(STEEL, 0.1))
    canvas.vline(13, 2, 13, darken(STEEL, 0.55))
    canvas.rect(4, 4, 11, 8, base)
    canvas.hline(4, 11, 4, lighten(base, 0.3))
    canvas.hline(5, 9, 6, darken(base, 0.45))
    for x in range(4, 12, 2):
        canvas.set(x, 11, GOLD)
    canvas.set(12, 3, LED_GREEN)
    return canvas


def item_tape(base, key):
    """A tape cartridge: dark shell, two reels behind a window, a label strip."""
    canvas = Canvas()
    canvas.rect(1, 3, 14, 12, base)
    canvas.hline(1, 14, 3, lighten(base, 0.25))
    canvas.rect(3, 5, 12, 9, (24, 24, 28))
    for cx in (5.5, 10.5):
        canvas.disc(cx, 7, 1.8, (110, 70, 40))
        canvas.disc(cx, 7, 0.8, lighten(STEEL, 0.2))
    canvas.hline(3, 12, 11, (230, 226, 210))
    canvas.hline(4, 9, 11, (90, 140, 200))
    return canvas


def item_pattern(base, key, encoded=False):
    canvas = Canvas()
    canvas.rect(3, 1, 12, 14, base)
    canvas.vline(12, 1, 14, darken(base, 0.25))
    canvas.hline(3, 12, 14, darken(base, 0.25))
    ink = (40, 90, 170) if encoded else darken(base, 0.35)
    for y in (4, 7, 10):
        for x in (5, 8):
            canvas.rect(x, y, x + 1, y + 1, ink if encoded else darken(base, 0.15))
    canvas.set(11, 3, LED_GREEN if encoded else darken(base, 0.3))
    if encoded:
        canvas.line(5, 12, 10, 12, ink)
    return canvas


def item_wireless(base, key):
    canvas = Canvas()
    canvas.rect(3, 3, 12, 15, base)
    canvas.vline(3, 3, 15, lighten(base, 0.25))
    canvas.rect(4, 5, 11, 10, GLASS_DARK)
    for x, y in ((5, 6), (7, 6), (9, 6), (5, 8), (7, 8)):
        canvas.set(x, y, LED_GREEN)
    canvas.vline(11, 0, 3, STEEL)
    canvas.set(11, 0, LED_RED)
    canvas.rect(5, 12, 10, 13, darken(base, 0.3))
    return canvas


def item_wafer(base, key):
    """Wafer-Scale Engine: a whole silicon wafer, a grid of dies shimmering across it, with a flat edge."""
    canvas = Canvas()
    for y in range(SIZE):
        for x in range(SIZE):
            distance = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if distance <= 7.2 and y < 15:
                shimmer = ((x + y) % 5) / 10
                canvas.set(x, y, mix(lighten(base, 0.35 + shimmer * 0.4), darken(base, 0.25), distance / 7.2))
    for line in range(3, 14, 3):
        for step in range(SIZE):
            for (x, y) in ((line, step), (step, line)):
                if math.hypot(x + 0.5 - 8, y + 0.5 - 8) <= 6.6 and y < 15:
                    canvas.set(x, y, darken(base, 0.4))
    canvas.hline(4, 11, 14, darken(base, 0.5))
    canvas.set(6, 5, (255, 255, 255))
    canvas.set(10, 9, (230, 245, 255))
    return canvas


def item_weights(base, key):
    """AGI Weights: a battered thumb drive with a single red light that is definitely looking at you."""
    canvas = Canvas()
    canvas.rect(5, 2, 10, 5, (190, 196, 204))
    canvas.rect(6, 3, 6, 3, (60, 60, 70))
    canvas.rect(9, 3, 9, 3, (60, 60, 70))
    canvas.rect(4, 6, 11, 14, darken(base, 0.1))
    canvas.bevel(4, 6, 11, 14, lighten(base, 0.3), darken(base, 0.5))
    canvas.rect(6, 8, 9, 9, (240, 240, 240))
    canvas.set(7, 8, (255, 40, 40))
    canvas.set(8, 8, (255, 40, 40))
    canvas.hline(5, 10, 12, lighten(base, 0.2))
    return canvas


ITEM_STYLES = {
    "wafer": item_wafer,
    "weights": item_weights,
    "drive": item_drive,
    "tape": item_tape,
    "pattern": lambda base, key: item_pattern(base, key),
    "pattern_encoded": lambda base, key: item_pattern(base, key, encoded=True),
    "wireless": item_wireless,
    "asic": item_asic,
    "multimeter": item_multimeter,
    "lump": lambda base, key: item_lump(base, key),
    "coke": lambda base, key: item_lump(base, key, glints=True),
    "ingot": item_ingot,
    "crystal": item_crystal,
    "orb": item_orb,
    "spool": item_spool,
    "circuit": item_circuit,
    "chip": item_chip,
    "ram": item_ram,
    "coil": item_coil,
    "board": item_board,
    "server": lambda base, key: item_server(base, key),
    "gpu": lambda base, key: item_server(base, key, gpu=True),
    "failed": lambda base, key: item_server(base, key, failed=True),
    "scanner": item_scanner,
    "toolkit": item_toolkit,
    "cell": item_cell,
    "tank": item_tank,
    "pellets": item_pellets,
    "jerrycan": item_jerrycan,
    "book": item_book,
}


def item_texture(entry):
    base = rgb(entry["color"])
    canvas = ITEM_STYLES[entry["pattern"]](base, entry["id"])
    return outline(canvas, base)


# ---------------------------------------------------------------- AI contracts, training data, water

PAPER = (236, 230, 210)
INK = (40, 44, 60)
CRAYON_COLOURS = [(226, 66, 58), (246, 192, 72), (92, 170, 255), (110, 196, 90), (200, 110, 220)]
WATER = (64, 118, 228)


def pump_face(base, key, on):
    """Freshwater pump: an intake pipe, a pressure gauge, and water moving through a sight glass when running."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = plate(base, key)
        canvas.inset(2, 2, 13, 6, GLASS_DARK, lighten(base, 0.2), darken(base, 0.6))
        for x in range(3, 13):
            if on and (x + frame) % 4 != 0:
                canvas.set(x, 4, WATER)
                canvas.set(x, 3, lighten(WATER, 0.3) if (x + frame) % 4 == 1 else WATER)
        canvas.disc(5, 10.5, 2.6, lighten(STEEL, 0.2))
        canvas.disc(5, 10.5, 1.8, (230, 230, 220))
        canvas.line(5, 10, 6 if on else 4, 9, LED_RED)
        canvas.rect(9, 8, 13, 13, darken(STEEL, 0.15))
        canvas.disc(11, 10.5, 1.7, darken(base, 0.5))
        canvas.set(11, 10, LED_GREEN if on else LED_OFF)
        frames.append(canvas)
    return frames


def easel_face(base, key, on):
    """Kids' art table: a sheet of paper on wood, crayon scribbles appearing while kids draw."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = noisy(base, key + ":wood", 0.08, streak=True)
        canvas.rect(2, 2, 13, 12, PAPER)
        canvas.bevel(2, 2, 13, 12, lighten(PAPER, 0.1), darken(PAPER, 0.2))
        rng = rng_for(key + ":scribble")
        strokes = 3 + (frame if on else 1)
        for stroke in range(strokes):
            colour = CRAYON_COLOURS[stroke % len(CRAYON_COLOURS)]
            x0, y0 = rng.randrange(3, 12), rng.randrange(3, 11)
            canvas.line(x0, y0, min(12, x0 + rng.randrange(-3, 4)), min(11, y0 + rng.randrange(-2, 3)), colour)
        canvas.disc(5, 6, 1.6, CRAYON_COLOURS[1])
        for index, colour in enumerate(CRAYON_COLOURS):
            canvas.rect(3 + index * 2, 14, 3 + index * 2, 15, colour)
        frames.append(canvas)
    return frames


def scriptorium_face(base, key, on):
    """Scriptorium desk: an open book with lines being written, and shackles bolted to the front."""
    frames = []
    for frame in range(3 if on else 1):
        canvas = noisy(base, key + ":wood", 0.08, streak=True)
        canvas.rect(1, 2, 7, 10, PAPER)
        canvas.rect(8, 2, 14, 10, PAPER)
        canvas.vline(7, 2, 10, darken(PAPER, 0.3))
        lines = 4 if not on else 2 + frame
        for row in range(4):
            y = 4 + row * 2
            canvas.hline(2, 6, y, INK if row < lines else darken(PAPER, 0.08))
            canvas.hline(9, 13 - (row % 2), y, INK if row + 2 < lines else darken(PAPER, 0.08))
        canvas.line(12, 1, 14, 5, (230, 230, 230))
        for x in range(1, 15, 2):
            canvas.set(x, 13, darken(STEEL, 0.25))
            canvas.set(x + 1, 13, lighten(STEEL, 0.1))
        canvas.disc(3, 13, 1.6, darken(STEEL, 0.1), inner=0.7)
        canvas.disc(12.5, 13, 1.6, darken(STEEL, 0.1), inner=0.7)
        frames.append(canvas)
    return frames


def ops_face(base, key, on):
    """Operations terminal: a four-panel dashboard with a graph, bars, a list and an alert light."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = plate(base, key)
        canvas.inset(1, 1, 14, 12, GLASS_DARK, lighten(base, 0.2), darken(base, 0.6))
        if on:
            graph = [10, 9, 9, 7, 8, 6, 5, 6]
            for x, y in enumerate(graph):
                canvas.set(2 + x, y - 4 + (1 if (x + frame) % 5 == 0 else 0), LED_GREEN)
            for bar in range(3):
                height = 2 + (bar * 2 + frame) % 4
                canvas.vline(11 + bar, 6 - height, 5, LED_BLUE)
            for row in range(3):
                canvas.hline(2, 7 - row, 8 + row, (200, 210, 214))
            canvas.rect(10, 8, 13, 10, LED_AMBER if frame % 2 else darken(LED_AMBER, 0.4))
        canvas.rect(4, 13, 11, 14, darken(base, 0.3))
        for x in range(5, 11, 2):
            canvas.set(x, 13, lighten(base, 0.3))
        frames.append(canvas)
    return frames


FRONT_STYLES["pump"] = pump_face
FRONT_STYLES["easel"] = easel_face
FRONT_STYLES["scriptorium"] = scriptorium_face
FRONT_STYLES["ops"] = ops_face


def top_water(base, key):
    canvas = plate(base, key + ":water")
    canvas.disc(8, 8, 4.5, darken(STEEL, 0.15))
    canvas.disc(8, 8, 3.2, WATER)
    canvas.disc(7, 7, 1.0, lighten(WATER, 0.4))
    return canvas


def top_desk(base, key):
    canvas = noisy(base, key + ":desktop", 0.08, streak=True)
    canvas.rect(3, 4, 7, 9, PAPER)
    canvas.rect(10, 3, 11, 4, INK)
    canvas.line(11, 3, 13, 1, (230, 230, 230))
    return canvas


TOP_STYLES["water"] = top_water
TOP_STYLES["desk"] = top_desk


def item_tensor(base, key):
    """A 2U blade with a grid of orange tensor cores."""
    canvas = item_server(base, key, gpu=True)
    for y in (6, 8, 10):
        for x in range(3, 13, 2):
            canvas.set(x, y, (246, 140, 60))
    return canvas


def item_crayons(base, key):
    canvas = Canvas()
    for index, colour in enumerate(CRAYON_COLOURS):
        x = 3 + index * 2
        canvas.rect(x, 2 + (index % 2), x + 1, 9, colour)
        canvas.set(x, 1 + (index % 2), darken(colour, 0.2))
    canvas.rect(2, 8, 13, 14, base)
    canvas.hline(2, 13, 8, lighten(base, 0.3))
    canvas.rect(4, 10, 11, 12, PAPER)
    canvas.hline(5, 10, 11, CRAYON_COLOURS[0])
    return canvas


def item_shackles(base, key):
    canvas = Canvas()
    canvas.disc(4.5, 5, 3.4, base, inner=2.0)
    canvas.disc(11.5, 11, 3.4, base, inner=2.0)
    for step in range(4):
        x, y = 7 + step, 7 + step
        canvas.set(x, y, lighten(base, 0.25) if step % 2 else darken(base, 0.25))
    canvas.set(3, 2, lighten(base, 0.4))
    canvas.set(10, 8, lighten(base, 0.4))
    return canvas


def item_art_aggregate(base, key):
    canvas = Canvas()
    for offset in (2, 1, 0):
        canvas.rect(2 + offset, 2 + offset, 13 - (2 - offset), 13 - (2 - offset), darken(PAPER, 0.08 * offset))
    rng = rng_for(key + ":art")
    for stroke in range(6):
        colour = CRAYON_COLOURS[stroke % len(CRAYON_COLOURS)]
        x0, y0 = rng.randrange(3, 11), rng.randrange(3, 11)
        canvas.line(x0, y0, x0 + rng.randrange(-2, 3), y0 + rng.randrange(-2, 3), colour)
    canvas.disc(10, 5, 1.4, CRAYON_COLOURS[1])
    canvas.hline(2, 13, 8, base)
    return canvas


def item_corpus(base, key):
    canvas = Canvas()
    canvas.rect(3, 1, 12, 14, PAPER)
    canvas.vline(12, 1, 14, darken(PAPER, 0.25))
    canvas.hline(3, 12, 14, darken(PAPER, 0.25))
    for y in range(3, 13, 2):
        canvas.hline(4, 11 - (y % 3), y, INK)
    canvas.vline(7, 1, 14, base)
    canvas.hline(3, 12, 7, base)
    canvas.disc(7.5, 7.5, 1.4, darken(base, 0.2))
    return canvas


def item_generated_image(base, key):
    canvas = Canvas()
    canvas.rect(1, 2, 14, 13, base)
    canvas.rect(2, 3, 13, 12, (120, 180, 230))
    canvas.rect(2, 9, 13, 12, (100, 170, 80))
    canvas.poly([(4, 9), (7, 5), (10, 9)], (130, 130, 140))
    canvas.disc(11, 5, 1.5, (250, 220, 90))
    canvas.set(13, 3, (255, 255, 255))
    canvas.set(12, 2, (255, 255, 255))
    canvas.set(14, 2, (255, 255, 255))
    return canvas


def item_generated_document(base, key):
    canvas = Canvas()
    canvas.rect(3, 1, 12, 14, PAPER)
    canvas.vline(12, 1, 14, darken(PAPER, 0.25))
    for y in range(3, 13, 2):
        canvas.hline(4, 10 - (y % 4), y, INK)
    canvas.rect(10, 10, 14, 14, base)
    canvas.set(12, 11, (255, 255, 255))
    canvas.hline(11, 13, 12, (255, 255, 255))
    canvas.set(12, 13, (255, 255, 255))
    return canvas


ITEM_STYLES["tensor"] = item_tensor


def item_powder(base, key):
    """A heap of powder, like yellowcake."""
    canvas = Canvas()
    rng = rng_for(key)
    for y in range(SIZE):
        for x in range(SIZE):
            height = 12 - abs(x - 7.5) * 0.9
            if 6 + (12 - height) * 0.5 <= y <= 13 and rng.random() < 0.94:
                canvas.set(x, y, lighten(base, 0.2) if y < 9 else darken(base, rng.uniform(0, 0.25)))
    return canvas


def item_rod(base, key):
    """A fuel rod, still glowing."""
    canvas = Canvas()
    for i in range(10):
        x, y = 3 + i, 12 - i
        canvas.set(x, y, (150, 160, 166))
        canvas.set(x + 1, y, base)
        canvas.set(x, y - 1, lighten(base, 0.35))
        canvas.set(x + 1, y + 1, darken(base, 0.3))
    canvas.set(3, 13, (90, 96, 100))
    canvas.set(13, 2, (90, 96, 100))
    canvas.set(8, 7, (230, 255, 230))
    return canvas


ITEM_STYLES["powder"] = item_powder
ITEM_STYLES["rod"] = item_rod
ITEM_STYLES["crayons"] = item_crayons
ITEM_STYLES["shackles"] = item_shackles
ITEM_STYLES["art_aggregate"] = item_art_aggregate
ITEM_STYLES["corpus"] = item_corpus
ITEM_STYLES["generated_image"] = item_generated_image
ITEM_STYLES["generated_document"] = item_generated_document


def side_wood(base, key):
    canvas = noisy(base, key + ":planks", 0.08, streak=True)
    for y in (4, 9, 14):
        canvas.hline(0, 15, y, darken(base, 0.3))
    canvas.vline(1, 0, 15, darken(base, 0.2))
    canvas.vline(14, 0, 15, darken(base, 0.2))
    return canvas


SIDE_STYLES["wood"] = side_wood


# ---------------------------------------------------------------- smog: scrubber, respirator, offsets, effect icons

SMOG = (107, 94, 62)


def scrubber_face(base, key, on):
    """Smog scrubber: a pleated filter behind a grille, with a dial that spins while it runs."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = plate(base, key)
        canvas.inset(2, 2, 13, 10, darken(base, 0.55), lighten(base, 0.2), darken(base, 0.6))
        for x in range(3, 13):
            pleat = (230, 226, 210) if x % 2 == 0 else (196, 190, 170)
            dirty = 0.35 if not on else 0.15 * ((x + frame) % 3)
            canvas.vline(x, 3, 9, mix(pleat, SMOG, dirty))
        for y in (4, 7):
            canvas.hline(2, 13, y, darken(STEEL, 0.2))
        canvas.disc(5, 13, 1.7, darken(STEEL, 0.1))
        angle = frame * math.pi / 2
        canvas.set(5 + round(math.cos(angle)), 13 + round(math.sin(angle)), LED_GREEN if on else LED_OFF)
        canvas.rect(9, 12, 13, 13, LED_GREEN if on else LED_OFF)
        frames.append(canvas)
    return frames


FRONT_STYLES["scrubber"] = scrubber_face


def item_respirator(base, key):
    """A half-face mask: a grey shell, two charcoal filter cans and a strap."""
    canvas = Canvas()
    canvas.hline(1, 14, 5, darken(base, 0.4))
    canvas.hline(1, 14, 4, darken(base, 0.2))
    canvas.poly([(4, 5), (11, 5), (13, 9), (10, 13), (5, 13), (2, 9)], base)
    canvas.hline(5, 10, 6, lighten(base, 0.25))
    canvas.vline(7, 9, 12, darken(base, 0.35))
    for cx in (3.5, 11.5):
        canvas.disc(cx, 11, 2.4, (54, 54, 58))
        canvas.disc(cx, 11, 1.4, (30, 30, 32))
        canvas.set(int(cx), 10, (90, 90, 96))
    return outline(canvas, base)


def item_carbon_offset(base, key):
    """A green certificate with a gold seal and a leaf."""
    canvas = Canvas()
    canvas.rect(1, 3, 14, 12, (236, 240, 220))
    canvas.frame(1, 3, 14, 12, base)
    canvas.frame(2, 4, 13, 11, lighten(base, 0.4))
    for y in (6, 8):
        canvas.hline(4, 9, y, (120, 130, 120))
    canvas.disc(11, 9, 2.2, GOLD)
    canvas.set(11, 9, darken(GOLD, 0.3))
    canvas.line(4, 10, 6, 9, lighten(base, 0.1))
    canvas.set(4, 9, base)
    canvas.set(11, 12, (200, 60, 50))
    canvas.set(11, 13, (200, 60, 50))
    return canvas


ITEM_STYLES["respirator"] = item_respirator
ITEM_STYLES["carbon_offset"] = item_carbon_offset


def png_rgba(width, height, pixels):
    """Encode an arbitrary-size image; pixels[y][x] is an (r, g, b, a) tuple or None for transparent."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    rows = b"".join(b"\0" + bytes(channel for pixel in row for channel in (pixel or (0, 0, 0, 0))) for row in pixels)
    header = struct.pack(">2I5B", width, height, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")


def effect_icon(kind):
    """18 x 18 status effect icons: a dizzy swirl, or a cough cloud."""
    pixels = [[None] * 18 for _ in range(18)]

    def put(x, y, colour):
        if 0 <= x < 18 and 0 <= y < 18:
            pixels[y][x] = (*colour, 255)

    if kind == "dizzy":
        for step in range(70):
            t = step / 70 * 2.6 * math.pi
            r = 1 + t * 0.85
            put(round(9 + r * math.cos(t)), round(9 + r * math.sin(t)), mix((214, 200, 150), SMOG, step / 90))
        for x, y in ((3, 3), (14, 4), (4, 14)):
            put(x, y, (246, 222, 120))
    elif kind == "radiation":
        # A trefoil on a yellow disc.
        for y in range(18):
            for x in range(18):
                dx, dy = x - 8.5, y - 8.5
                distance = math.hypot(dx, dy)
                if distance > 8:
                    continue
                angle = (math.degrees(math.atan2(-dy, dx)) - 90) % 120
                blade = 1.6 < distance < 6.8 and (angle < 30 or angle > 90)
                put(x, y, (24, 24, 28) if distance < 1.3 or blade else (236, 206, 64))
    else:
        for cx, cy, radius in ((6, 10, 3.4), (10, 8, 4.0), (13, 11, 3.0), (9, 12, 3.2)):
            for y in range(18):
                for x in range(18):
                    if (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2:
                        shade = 0.25 + 0.5 * (y / 18)
                        put(x, y, mix((170, 166, 156), (70, 66, 60), shade))
        for x, y in ((2, 6), (1, 9), (3, 12)):
            put(x, y, (120, 116, 108))
    return png_rgba(18, 18, pixels)


def respirator_armor_layer():
    """The worn mask, on the 64 x 32 armor layout: only the helmet's front face, lower half, is painted."""
    pixels = [[None] * 64 for _ in range(32)]
    shell = (150, 156, 160, 255)
    dark = (60, 62, 66, 255)
    strap = (70, 60, 50, 255)
    # Helmet front face is u 8..15, v 8..15; the strap wraps the sides (u 0..7 and 16..23) and back (24..31).
    for x in range(0, 32):
        pixels[11][x] = strap
    for y in range(12, 16):
        for x in range(9, 15):
            pixels[y][x] = shell
    for x, y in ((9, 14), (10, 14), (13, 14), (14, 14), (9, 15), (14, 15)):
        pixels[y][x] = dark
    pixels[13][11] = dark
    pixels[13][12] = dark
    return png_rgba(64, 32, pixels)


# ---------------------------------------------------------------- industry: belts, robot arms, drones, hydrogen

BELT_RUBBER = (40, 42, 46)
BELT_FRAMES = 4


def belt_top(entry):
    """A rubber belt between steel rails, its cleats sliding toward the front (north, the top of the texture) one
    pixel a frame, so a placed belt visibly runs the way it faces."""
    frames = []
    for frame in range(BELT_FRAMES):
        canvas = Canvas()
        for y in range(SIZE):
            for x in range(2, 14):
                canvas.set(x, y, BELT_RUBBER if (x + y) % 5 else lighten(BELT_RUBBER, 0.05))
            # Cleats every four rows, moving up the texture (toward the belt's front) as frames advance.
            if (y + frame) % 4 == 0:
                canvas.hline(3, 12, y, lighten(BELT_RUBBER, 0.22))
        for x in (0, 1, 14, 15):
            canvas.vline(x, 0, 15, STEEL if x in (0, 15) else darken(STEEL, 0.3))
        for y in range(1, 16, 4):
            canvas.set(0, y, lighten(STEEL, 0.3))
            canvas.set(15, y, lighten(STEEL, 0.3))
        frames.append(canvas)
    return frames


def belt_side(entry):
    """The belt's side frame: a steel channel with roller ends showing."""
    canvas = Canvas()
    canvas.rect(0, 11, 15, 15, darken(STEEL, 0.25))
    canvas.hline(0, 15, 11, lighten(STEEL, 0.2))
    canvas.hline(0, 15, 15, darken(STEEL, 0.5))
    for x in (2, 7, 12):
        canvas.rect(x, 12, x + 1, 13, darken(STEEL, 0.55))
        canvas.set(x, 12, BLACK)
    canvas.rect(0, 10, 15, 10, BELT_RUBBER)
    return canvas


def belt_bottom(entry):
    canvas = plate(darken(STEEL, 0.35), entry["id"] + ":bottom")
    for x in (3, 8, 13):
        canvas.vline(x, 1, 14, darken(STEEL, 0.55))
    return canvas


def arm_base(entry):
    """The arm's floor plate: painted steel with a hazard band round the edge, bolted down at the corners."""
    base = rgb(entry["color"])
    canvas = plate(darken(STEEL, 0.15), entry["id"] + ":base", rivets=True)
    for i in range(SIZE):
        for x, y in ((i, 0), (i, 15), (0, i), (15, i)):
            canvas.set(x, y, YELLOW if ((x + y) // 2) % 2 == 0 else BLACK)
    canvas.rect(4, 4, 11, 11, base)
    canvas.frame(4, 4, 11, 11, darken(base, 0.4))
    return canvas


def arm_column(entry):
    """The turret column the arm sits on, in the arm's colour with a dark seam."""
    base = rgb(entry["color"])
    canvas = plate(base, entry["id"] + ":column")
    canvas.hline(0, 15, 7, darken(base, 0.45))
    canvas.rect(6, 10, 9, 12, darken(base, 0.5))
    return canvas


def arm_light(entry, on):
    """A status beacon: dark when idle, flashing amber while the arm works."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = plate(darken(STEEL, 0.4), entry["id"] + ":light")
        glow = mix(LED_AMBER, (255, 240, 180), [0.0, 0.6, 1.0, 0.6][frame]) if on else LED_OFF
        canvas.disc(8, 8, 5, glow)
        canvas.disc(8, 8, 2, lighten(glow, 0.3))
        frames.append(canvas)
    return frames


def _strip(canvases):
    """Several 16x16 tiles side by side, for the parts the client draws as boxes (each face gets a whole tile)."""
    rows = []
    for y in range(SIZE):
        row = []
        for canvas in canvases:
            for x in range(SIZE):
                pixel = canvas.px[y][x]
                row.append(None if pixel is None else (*pixel[:3], pixel[3] if len(pixel) > 3 else 255))
        rows.append(row)
    return png_rgba(SIZE * len(canvases), SIZE, rows)


def arm_parts(entry):
    """Tiles for the moving arm, drawn by the block entity renderer: 0 segment, 1 joint, 2 tool head, 3 accent."""
    base = rgb(entry["color"])
    segment = plate(base, entry["id"] + ":segment")
    segment.hline(0, 15, 4, darken(base, 0.3))
    segment.hline(0, 15, 11, darken(base, 0.3))
    for x in (2, 13):
        segment.set(x, 7, lighten(base, 0.35))
    joint = plate(darken(STEEL, 0.45), entry["id"] + ":joint")
    joint.disc(8, 8, 5, darken(STEEL, 0.2))
    joint.disc(8, 8, 2, lighten(STEEL, 0.2))
    tool = plate(darken(STEEL, 0.3), entry["id"] + ":tool")
    if entry["id"] == "welding_arm":
        tool.rect(5, 0, 10, 15, COPPER)
        tool.rect(6, 10, 9, 15, (255, 190, 90))
        tool.rect(7, 13, 8, 15, (255, 250, 220))
    elif entry["id"] == "riveting_arm":
        tool.rect(4, 0, 11, 9, darken(STEEL, 0.1))
        tool.rect(7, 9, 8, 15, lighten(STEEL, 0.35))
        tool.hline(4, 11, 4, BLACK)
    else:
        tool.rect(2, 0, 4, 15, lighten(STEEL, 0.2))
        tool.rect(11, 0, 13, 15, lighten(STEEL, 0.2))
        tool.rect(5, 0, 10, 4, darken(STEEL, 0.5))
    accent = plate(darken(base, 0.35), entry["id"] + ":accent")
    for i in range(SIZE):
        accent.set(i, (i * 3) % 16, YELLOW)
    return _strip([segment, joint, tool, accent])


def drone_parts():
    """Tiles for the Maintenance Drone: 0 body, 1 boom, 2 rotor blur, 3 camera and lights."""
    shell = (222, 160, 64)
    body = plate(shell, "drone:body")
    body.rect(5, 5, 10, 10, darken(shell, 0.35))
    body.rect(6, 6, 9, 9, (40, 120, 200))
    body.set(2, 2, LED_GREEN)
    body.set(13, 2, LED_RED)
    boom = plate((70, 74, 80), "drone:boom")
    boom.hline(0, 15, 8, (110, 116, 124))
    rotor = Canvas()
    for y in range(SIZE):
        for x in range(SIZE):
            distance = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if distance <= 7.5:
                rotor.set(x, y, (200, 206, 214, 70) if distance > 1.6 else (60, 60, 64, 255))
    rotor.line(2, 8, 13, 8, (190, 196, 204, 200))
    camera = plate((34, 36, 40), "drone:camera")
    camera.disc(8, 8, 4, (16, 18, 22))
    camera.disc(8, 8, 2, (70, 160, 230))
    camera.set(7, 7, (230, 245, 255))
    return _strip([body, boom, rotor, camera])


def hatch_face(base, key, on):
    """The Drone Dock's front: a roller hatch with a status strip that pulses while drones are out."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = plate(base, key + ":hatch")
        canvas.inset(2, 3, 13, 13, darken(base, 0.4), lighten(base, 0.1), darken(base, 0.6))
        for y in range(4, 13, 2):
            canvas.hline(3, 12, y, darken(base, 0.2))
        light = mix(LED_GREEN, (200, 255, 210), [0.0, 0.6, 1.0, 0.6][frame]) if on else LED_OFF
        canvas.hline(3, 12, 1, light)
        frames.append(canvas)
    return frames


def top_helipad(base, key):
    """A landing pad: a yellow circle with an H."""
    canvas = plate(darken(base, 0.25), key + ":pad")
    canvas.disc(8, 8, 7, YELLOW, inner=5.8)
    for y in range(4, 12):
        canvas.set(5, y, (236, 236, 230))
        canvas.set(10, y, (236, 236, 230))
    canvas.hline(5, 10, 8, (236, 236, 230))
    return canvas


def _electrolyser_face(canvas, base, frame, on):
    """Two electrodes in a water tank, streams of bubbles rising off them while it runs."""
    canvas.rect(2, 3, 13, 13, (30, 70, 110))
    canvas.hline(2, 13, 3, (90, 160, 210))
    for x in (5, 10):
        canvas.rect(x, 5, x + 1, 13, (180, 186, 196) if x == 5 else COPPER)
        canvas.rect(x, 1, x + 1, 2, darken(base, 0.5))
    if on:
        for column, x in enumerate((4, 7, 9, 12)):
            for y in range(4, 13):
                if (y + frame * 2 + column * 3) % 5 == 0:
                    canvas.set(x, y, (210, 235, 255))
    canvas.set(4, 2, (230, 80, 70))
    canvas.set(11, 2, (90, 160, 240))


FORMED_FACES["electrolyser"] = _electrolyser_face
FRONT_STYLES["hatch_face"] = hatch_face
TOP_STYLES["helipad"] = top_helipad


def item_h2(base, key):
    """A gas bottle with a blue shoulder and an H2 label."""
    canvas = Canvas()
    canvas.rect(5, 4, 10, 14, (196, 202, 210))
    canvas.vline(5, 4, 14, (230, 234, 240))
    canvas.vline(10, 4, 14, (130, 136, 146))
    canvas.rect(5, 4, 10, 6, base)
    canvas.vline(5, 4, 6, lighten(base, 0.3))
    canvas.rect(7, 2, 8, 3, darken(STEEL, 0.3))
    canvas.hline(6, 9, 1, BLACK)
    for y in range(8, 12):
        canvas.set(6, y, (40, 90, 160))
        canvas.set(8, y, (40, 90, 160))
    canvas.set(7, 9, (40, 90, 160))
    canvas.set(9, 11, (40, 90, 160))
    return canvas


def item_motor(base, key):
    """A motor can with copper windings showing and a shaft out of the front."""
    canvas = Canvas()
    canvas.rect(3, 5, 11, 12, darken(STEEL, 0.1))
    canvas.hline(3, 11, 5, lighten(STEEL, 0.25))
    canvas.hline(3, 11, 12, darken(STEEL, 0.45))
    for x in range(4, 11, 2):
        canvas.vline(x, 7, 10, base)
    canvas.rect(12, 8, 14, 9, lighten(STEEL, 0.3))
    canvas.rect(2, 13, 12, 13, darken(STEEL, 0.5))
    return canvas


def item_drone_frame(base, key):
    """An X of bare booms with a centre plate: the drone before the line gets to it."""
    canvas = Canvas()
    canvas.line(2, 2, 13, 13, base)
    canvas.line(13, 2, 2, 13, base)
    canvas.line(3, 2, 13, 12, darken(base, 0.3))
    canvas.line(12, 2, 2, 12, darken(base, 0.3))
    canvas.rect(6, 6, 9, 9, darken(base, 0.2))
    canvas.frame(6, 6, 9, 9, darken(base, 0.5))
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        canvas.set(x, y, lighten(base, 0.3))
    return canvas


def item_drone(base, key):
    """The finished quadcopter, seen from above: four rotors round an orange body."""
    canvas = Canvas()
    canvas.line(3, 3, 12, 12, (70, 74, 80))
    canvas.line(12, 3, 3, 12, (70, 74, 80))
    for cx, cy in ((3, 3), (12, 3), (3, 12), (12, 12)):
        canvas.disc(cx + 0.5, cy + 0.5, 2.8, (190, 198, 208))
        canvas.set(cx, cy, BLACK)
    canvas.rect(5, 5, 10, 10, base)
    canvas.frame(5, 5, 10, 10, darken(base, 0.4))
    canvas.rect(7, 7, 8, 8, (60, 150, 230))
    canvas.set(5, 5, LED_GREEN)
    canvas.set(10, 5, LED_RED)
    return canvas


ITEM_STYLES["h2"] = item_h2
ITEM_STYLES["motor"] = item_motor
ITEM_STYLES["drone_frame"] = item_drone_frame
ITEM_STYLES["drone"] = item_drone


# ---------------------------------------------------------------- the launch programme

def launch_pad_block(entry):
    """Scorched concrete in big cast slabs, a yellow edge line and a blackened centre where the engines hit."""
    base = rgb(entry["color"])
    canvas = noisy(base, entry["id"], 0.05)
    canvas.hline(0, 15, 7, darken(base, 0.2))
    canvas.vline(7, 0, 15, darken(base, 0.2))
    for i in range(SIZE):
        canvas.set(i, 0, YELLOW if (i // 2) % 2 == 0 else darken(base, 0.4))
    rng = rng_for(entry["id"] + ":scorch")
    for y in range(SIZE):
        for x in range(SIZE):
            if rng.random() < 0.12 * (1 - math.hypot(x - 8, y - 9) / 11):
                canvas.set(x, y, darken(base, 0.45))
    return canvas


def launch_face(base, key, on):
    """A console with a countdown readout and a big red launch button under a flip cover."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = plate(base, key + ":console")
        canvas.inset(2, 2, 13, 7, GLASS_DARK, lighten(base, 0.1), darken(base, 0.5))
        digits = (LED_GREEN if on else LED_OFF)
        for index, x in enumerate((3, 6, 9, 11)):
            lit = on and (index + frame) % 4 != 0
            canvas.rect(x, 4, x + 1, 5, digits if lit or not on else darken(digits, 0.5))
        canvas.disc(8, 11.5, 2.6, (200, 50, 40))
        canvas.disc(8, 11.5, 1.2, (240, 110, 90) if on and frame % 2 == 0 else (160, 40, 34))
        canvas.frame(4, 8, 11, 14, YELLOW)
        frames.append(canvas)
    return frames


def top_rectenna(base, key):
    """A grid of dipoles that glows faintly where the beam lands."""
    canvas = plate(darken(base, 0.3), key + ":rectenna")
    for y in range(2, 14, 3):
        for x in range(2, 14, 3):
            canvas.hline(x, x + 1, y, (200, 170, 90))
            canvas.set(x, y + 1, (120, 100, 60))
    canvas.frame(0, 0, 15, 15, darken(base, 0.6))
    return canvas


BLOCK_STYLES["launch_pad"] = launch_pad_block
FRONT_STYLES["launch"] = launch_face
TOP_STYLES["rectenna"] = top_rectenna


def _rocket_body(canvas, x0, x1, y0, y1, color):
    canvas.rect(x0, y0, x1, y1, color)
    canvas.vline(x0, y0, y1, lighten(color, 0.25))
    canvas.vline(x1, y0, y1, darken(color, 0.3))


def item_stage_frame(base, key):
    """A stage with no skin: ribs and a hollow tank, an empty engine mount underneath."""
    canvas = Canvas()
    for y in range(2, 13, 3):
        canvas.hline(5, 10, y, base)
    canvas.vline(5, 2, 12, darken(base, 0.2))
    canvas.vline(10, 2, 12, darken(base, 0.35))
    canvas.rect(6, 13, 9, 14, darken(STEEL, 0.4))
    return canvas


def item_rocket_stage(base, key):
    """A white stage with a black roll pattern band and an engine bell."""
    canvas = Canvas()
    _rocket_body(canvas, 5, 10, 1, 11, base)
    canvas.rect(5, 4, 7, 6, BLACK)
    canvas.rect(8, 7, 10, 9, BLACK)
    canvas.poly([(5.5, 12), (10.5, 12), (12, 15.5), (4, 15.5)], darken(STEEL, 0.35))
    canvas.set(3, 10, LED_RED)
    canvas.set(12, 10, LED_RED)
    return canvas


def item_bus(base, key):
    """A gold-foiled box with mounting rails."""
    canvas = Canvas()
    canvas.rect(4, 4, 11, 11, base)
    canvas.frame(4, 4, 11, 11, darken(base, 0.4))
    for x, y in ((5, 6), (8, 9), (10, 5), (6, 10)):
        canvas.set(x, y, lighten(base, 0.35))
    canvas.hline(2, 13, 7, darken(STEEL, 0.2))
    canvas.hline(2, 13, 8, darken(STEEL, 0.4))
    return canvas


def _satellite(canvas, body, wing):
    canvas.rect(6, 6, 9, 9, body)
    canvas.frame(6, 6, 9, 9, darken(body, 0.4))
    for x0 in (1, 11):
        canvas.rect(x0, 6, x0 + 3, 9, wing)
        canvas.vline(x0 + 2, 6, 9, darken(wing, 0.4))
    canvas.hline(4, 5, 7, STEEL)
    canvas.hline(10, 10, 7, STEEL)


def item_comms(base, key):
    canvas = Canvas()
    _satellite(canvas, base, (50, 80, 150))
    canvas.disc(8, 3.5, 2.6, (230, 230, 236))
    canvas.vline(8, 2, 5, darken(STEEL, 0.3))
    return canvas


def item_survey(base, key):
    canvas = Canvas()
    _satellite(canvas, base, (50, 80, 150))
    canvas.rect(7, 10, 8, 13, BLACK)
    canvas.set(7, 13, (90, 200, 255))
    return canvas


def item_orbital_dc(base, key):
    canvas = Canvas()
    canvas.rect(5, 3, 10, 12, base)
    canvas.frame(5, 3, 10, 12, darken(base, 0.45))
    for y in range(4, 12, 2):
        canvas.hline(6, 9, y, darken(base, 0.3))
        canvas.set(9, y, LED_GREEN)
    for x0 in (1, 12):
        canvas.rect(x0, 2, x0 + 2, 13, (40, 70, 140))
        for y in range(3, 13, 3):
            canvas.hline(x0, x0 + 2, y, (90, 130, 200))
    return canvas


def item_mirror(base, key):
    """A gold mirror petal catching the light, on a thin boom."""
    canvas = Canvas()
    canvas.poly([(8, 1), (14.5, 8), (8, 15), (1.5, 8)], base)
    canvas.poly([(8, 3), (12, 8), (8, 9)], lighten(base, 0.4))
    canvas.line(8, 1, 8, 15, darken(base, 0.3))
    canvas.set(8, 8, (255, 255, 230))
    return canvas


ITEM_STYLES.update({"stage_frame": item_stage_frame, "rocket_stage": item_rocket_stage, "bus": item_bus,
                    "comms": item_comms, "survey": item_survey, "orbital_dc": item_orbital_dc, "mirror": item_mirror})


# ---------------------------------------------------------------- the big rocket: one texture per stage, painted to size

class Sheet:
    """An arbitrary-size image for parts too big for one 16x16 tile (a stage's whole side, 8 px to a block)."""
    def __init__(self, width, height, fill):
        self.width, self.height = width, height
        self.px = [[(*fill, 255) for _ in range(width)] for _ in range(height)]

    def set(self, x, y, color):
        if 0 <= x < self.width and 0 <= y < self.height:
            self.px[y][x] = (*color[:3], 255)

    def rect(self, x0, y0, x1, y1, color):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, color)

    def png(self):
        return png_rgba(self.width, self.height, self.px)


ROCKET_WHITE = (236, 236, 232)
ROCKET_SHADE = (214, 214, 210)
ROCKET_BLACK = (26, 26, 30)


def _panel_lines(sheet, every, color):
    """Faint seams between the skin panels, so a 12-block stage doesn't read as one flat slab."""
    for y in range(0, sheet.height, every):
        for x in range(sheet.width):
            if sheet.px[y][x][:3] == ROCKET_WHITE:
                sheet.set(x, y, color)


def _letters(sheet, x, y, word, color):
    """Tall stencil letters, 3 px wide and 5 high, stacked down the side the way they are on the real thing."""
    glyphs = {"U": ["101", "101", "101", "101", "111"], "S": ["111", "100", "111", "001", "111"],
              "A": ["010", "101", "111", "101", "101"]}
    for index, letter in enumerate(word):
        for row, bits in enumerate(glyphs[letter]):
            for column, bit in enumerate(bits):
                if bit == "1":
                    for dy in range(2):
                        sheet.set(x + column * 2, y + index * 13 + row * 2 + dy, color)
                        sheet.set(x + column * 2 + 1, y + index * 13 + row * 2 + dy, color)


def rocket_first_stage():
    """The S-IC look: white, a black-and-white band at the top, USA stencilled down it, and a black skirt."""
    sheet = Sheet(24, 96, ROCKET_WHITE)
    _panel_lines(sheet, 8, ROCKET_SHADE)
    sheet.rect(0, 0, 11, 10, ROCKET_BLACK)
    sheet.rect(0, 11, 23, 12, ROCKET_SHADE)
    _letters(sheet, 9, 24, "USA", (40, 40, 44))
    sheet.rect(0, 84, 11, 95, ROCKET_BLACK)
    sheet.rect(12, 88, 23, 95, ROCKET_BLACK)
    for x in range(0, 24, 4):
        sheet.set(x, 83, ROCKET_SHADE)
    return sheet.png()


def rocket_second_stage():
    """The S-II look: white, with a black ring top and bottom and a thin stripe a third of the way down."""
    sheet = Sheet(24, 72, ROCKET_WHITE)
    _panel_lines(sheet, 9, ROCKET_SHADE)
    sheet.rect(0, 0, 23, 3, ROCKET_BLACK)
    sheet.rect(0, 22, 23, 22, ROCKET_BLACK)
    sheet.rect(0, 68, 23, 71, ROCKET_BLACK)
    return sheet.png()


def rocket_third_stage():
    """The S-IVB look: white, a black aft section on one half of each face, and a stripe near the top."""
    sheet = Sheet(16, 48, ROCKET_WHITE)
    _panel_lines(sheet, 8, ROCKET_SHADE)
    sheet.rect(0, 30, 7, 47, ROCKET_BLACK)
    sheet.rect(0, 4, 15, 4, ROCKET_BLACK)
    return sheet.png()


def rocket_parts():
    """Tiles for the rocket's smaller parts: 0 interstage, 1 engine bell, 2 instrument ring, 3 capsule and fairing,
    4 fin, 5 escape tower, 6 engine fairing."""
    white = ROCKET_WHITE
    interstage = plate((40, 42, 46), "rocket:interstage")
    for y in (3, 7, 11):
        interstage.hline(0, 15, y, (70, 72, 78))
    engine = plate((70, 66, 64), "rocket:engine")
    for y in range(SIZE):
        engine.hline(0, 15, y, mix((60, 58, 58), (150, 80, 50), y / 15))
    for x in (3, 8, 12):
        engine.vline(x, 0, 15, (40, 38, 38))
    ring = plate((170, 176, 182), "rocket:ring")
    ring.hline(0, 15, 5, (110, 116, 124))
    ring.hline(0, 15, 10, (110, 116, 124))
    capsule = plate(white, "rocket:capsule")
    for y in (4, 9, 14):
        capsule.hline(0, 15, y, (196, 198, 200))
    capsule.rect(6, 6, 9, 8, (60, 70, 90))
    fin = plate(white, "rocket:fin")
    fin.hline(0, 15, 15, ROCKET_BLACK)
    fin.vline(15, 0, 15, ROCKET_BLACK)
    tower = Canvas()
    for y in range(SIZE):
        for x in range(SIZE):
            if x in (0, 15) or (x - y) % 8 == 0 or (x + y) % 8 == 0:
                tower.set(x, y, (210, 80, 40))
    fairing = plate(white, "rocket:enginefairing")
    fairing.hline(0, 15, 0, ROCKET_BLACK)
    return _strip([interstage, engine, ring, capsule, fin, tower, fairing])


def lattice_block(entry):
    """An open steel truss: a frame and two diagonals per face, everything else see-through."""
    base = rgb(entry["color"])
    canvas = Canvas()
    for i in range(SIZE):
        for x, y in ((i, 0), (i, 15), (0, i), (15, i), (i, i), (i, 15 - i)):
            canvas.set(x, y, base)
    for i in range(SIZE):
        canvas.set(i, 1, darken(base, 0.3))
        canvas.set(1, i, lighten(base, 0.15))
    for x, y in ((0, 0), (15, 0), (0, 15), (15, 15)):
        canvas.set(x, y, darken(base, 0.45))
    return canvas


BLOCK_STYLES["lattice"] = lattice_block


# ---------------------------------------------------------------- wind and solar, end-game tier

def array_cells(entry, tracking=False):
    """Blue-black cells in an aluminium frame; the tracking array's cells are a deeper blue."""
    base = rgb(entry["color"])
    canvas = Canvas()
    canvas.rect(0, 0, 15, 15, (190, 196, 202))
    cell = darken(base, 0.35) if tracking else darken(base, 0.45)
    for cy in (1, 5, 9, 13):
        for cx in (1, 5, 9, 13):
            canvas.rect(cx, cy, cx + 1, cy + 1, cell)
            canvas.rect(cx + 2, cy, cx + 2, cy + 2, cell)
            canvas.rect(cx, cy + 2, cx + 1, cy + 2, cell)
            canvas.set(cx, cy, lighten(cell, 0.35))
    return canvas


def array_frame_texture(entry):
    canvas = plate((176, 182, 188), entry["id"] + ":frame")
    canvas.hline(0, 15, 8, (130, 136, 142))
    return canvas


def pole_side(entry):
    """A white tower in rolled sections, with a seam every half block and the odd rivet."""
    canvas = plate((226, 228, 226), entry["id"] + ":pole")
    for y in (0, 8):
        canvas.hline(0, 15, y, (190, 192, 190))
    for x in (2, 13):
        canvas.set(x, 4, (170, 172, 170))
        canvas.set(x, 12, (170, 172, 170))
    return canvas


def pole_top(entry):
    canvas = Canvas()
    canvas.rect(0, 0, 15, 15, (200, 202, 200))
    canvas.disc(8, 8, 5, (120, 124, 126))
    canvas.disc(8, 8, 3, (60, 62, 66))
    return canvas


def hub_face(base, key, on):
    """The nacelle's front: a bolted hub flange; the blades themselves are drawn turning in front of it."""
    canvas = plate(base, key + ":hub")
    canvas.disc(8, 8, 6, darken(base, 0.25))
    canvas.disc(8, 8, 4, lighten(base, 0.1))
    for angle in range(0, 360, 45):
        a = math.radians(angle)
        canvas.set(int(round(8 + math.cos(a) * 5)), int(round(8 + math.sin(a) * 5)), darken(base, 0.5))
    canvas.disc(8, 8, 1.5, (210, 60, 50) if on else darken(base, 0.4))
    return [canvas]


FRONT_STYLES["hub"] = hub_face


def rotor_parts():
    """Tiles for the Wind Tower's rotor: 0 blade, 1 blade tip (red, for aircraft), 2 hub cone."""
    blade = plate((236, 238, 236), "rotor:blade")
    blade.vline(4, 0, 15, (210, 212, 210))
    tip = plate((236, 238, 236), "rotor:tip")
    tip.rect(0, 0, 15, 7, (200, 50, 44))
    hub = plate((214, 218, 220), "rotor:hub")
    hub.disc(8, 8, 3, (180, 184, 188))
    return _strip([blade, tip, hub])


def item_array_frame(base, key):
    canvas = Canvas()
    canvas.frame(1, 4, 14, 11, base)
    canvas.vline(5, 4, 11, darken(base, 0.2))
    canvas.vline(10, 4, 11, darken(base, 0.2))
    canvas.hline(1, 14, 7, darken(base, 0.3))
    return canvas


def item_nacelle_frame(base, key):
    canvas = Canvas()
    canvas.rect(3, 5, 12, 10, base)
    canvas.frame(3, 5, 12, 10, darken(base, 0.4))
    canvas.disc(2.5, 7.5, 2.4, darken(base, 0.2))
    canvas.vline(7, 11, 14, darken(STEEL, 0.3))
    return canvas


def item_tower_frame(base, key):
    canvas = Canvas()
    canvas.disc(8, 8, 6, base, inner=4)
    canvas.disc(8, 8, 4.2, darken(base, 0.35), inner=3.6)
    for x, y in ((8, 2), (2, 8), (13, 8), (8, 13)):
        canvas.set(x, y, darken(base, 0.4))
    return canvas


ITEM_STYLES.update({"array_frame": item_array_frame, "nacelle_frame": item_nacelle_frame, "tower_frame": item_tower_frame})


def _arrow_face(base, key, on, into):
    """A belt port: a hatch with a chevron pointing out of the block (a loader) or into it (an unloader)."""
    canvas = plate(base, key + ":port")
    canvas.inset(2, 2, 13, 13, darken(base, 0.45), lighten(base, 0.1), darken(base, 0.6))
    chevron = (240, 220, 120) if on else (200, 190, 140)
    for i in range(4):
        y = 9 - i if not into else 6 + i
        canvas.hline(4 + i, 11 - i, y, chevron)
    canvas.hline(3, 12, 13, darken(base, 0.7))
    return [canvas]


FRONT_STYLES["loader"] = lambda base, key, on: _arrow_face(base, key, on, False)
FRONT_STYLES["unloader"] = lambda base, key, on: _arrow_face(base, key, on, True)


# ---------------------------------------------------------------- site construction

def planner_face(base, key, on):
    """A blueprint screen: a grid, a site outlined in white with rows of arrays on it, and a drone dot moving over it."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = plate(base, key + ":planner")
        canvas.inset(1, 2, 14, 13, (26, 64, 118), lighten(base, 0.1), darken(base, 0.6))
        grid = (44, 92, 150)
        for i in range(3, 14, 3):
            canvas.vline(i, 3, 12, grid)
        for i in range(4, 13, 3):
            canvas.hline(2, 13, i, grid)
        canvas.frame(4, 5, 11, 10, (220, 232, 245))
        for y in (6, 8):
            canvas.hline(5, 10, y, (110, 170, 230) if on else (80, 120, 160))
        if on:
            dx, dy = [(5, 6), (8, 6), (8, 8), (5, 8)][frame]
            canvas.set(dx, dy, YELLOW)
        canvas.hline(2, 13, 14, LED_GREEN if on else LED_OFF)
        frames.append(canvas)
    return frames


def item_stake(base, key):
    """A wooden stake with an orange survey flag."""
    canvas = Canvas()
    wood = (150, 110, 60)
    for i in range(10):
        canvas.set(4 + i // 2, 14 - i, wood)
        canvas.set(5 + i // 2, 14 - i, darken(wood, 0.25))
    canvas.set(4, 15, darken(wood, 0.4))
    canvas.poly([(9, 1), (15, 3), (9, 6)], base)
    canvas.line(9, 1, 9, 6, darken(base, 0.35))
    canvas.set(10, 3, lighten(base, 0.3))
    return canvas


def item_heavy_drone_frame(base, key):
    """A double-boomed X with a big centre plate and lifting hooks."""
    canvas = Canvas()
    for offset in (0, 1):
        canvas.line(1 + offset, 1, 14, 14 - offset, base)
        canvas.line(14 - offset, 1, 1, 14 - offset, darken(base, 0.25))
    canvas.rect(5, 5, 10, 10, darken(base, 0.15))
    canvas.frame(5, 5, 10, 10, darken(base, 0.5))
    canvas.rect(7, 7, 8, 8, darken(base, 0.45))
    for x, y in ((1, 1), (14, 1), (1, 14), (14, 14)):
        canvas.set(x, y, lighten(base, 0.35))
    return canvas


def _site_drone_icon(base, key, tool):
    """A big quadcopter seen from above, with something hanging off it (a gripper or a scoop)."""
    canvas = Canvas()
    canvas.line(2, 2, 13, 13, (70, 74, 80))
    canvas.line(13, 2, 2, 13, (70, 74, 80))
    for cx, cy in ((2, 2), (13, 2), (2, 13), (13, 13)):
        canvas.disc(cx + 0.5, cy + 0.5, 2.6, (190, 198, 208))
        canvas.set(cx, cy, BLACK)
    canvas.rect(4, 4, 11, 11, base)
    for i in range(4, 12):
        if (i // 2) % 2 == 0:
            canvas.set(i, 4, BLACK)
            canvas.set(i, 11, BLACK)
    canvas.frame(4, 4, 11, 11, darken(base, 0.45))
    if tool == "gripper":
        canvas.rect(6, 6, 9, 9, (60, 64, 70))
        canvas.set(6, 9, STEEL)
        canvas.set(9, 9, STEEL)
    else:
        canvas.rect(6, 6, 9, 9, (120, 90, 50))
        canvas.hline(6, 9, 6, (170, 176, 184))
    canvas.set(4, 5, LED_GREEN)
    canvas.set(11, 5, LED_RED)
    return canvas


def site_drone_parts(shell, key, scoop):
    """Tiles for a site drone: 0 striped body, 1 boom, 2 rotor blur, 3 the gripper or scoop."""
    body = plate(shell, key + ":body")
    for y in range(SIZE):
        for x in range(SIZE):
            if (x + y) // 3 % 2 == 0 and (y < 4 or y > 11):
                body.set(x, y, (30, 30, 34) if not scoop else darken(shell, 0.45))
    body.rect(5, 5, 10, 10, darken(shell, 0.35))
    body.rect(6, 6, 9, 9, (40, 120, 200))
    body.set(2, 6, LED_GREEN)
    body.set(13, 6, LED_RED)
    boom = plate((70, 74, 80), key + ":boom")
    boom.hline(0, 15, 8, (110, 116, 124))
    rotor = Canvas()
    for y in range(SIZE):
        for x in range(SIZE):
            distance = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if distance <= 7.5:
                rotor.set(x, y, (200, 206, 214, 70) if distance > 1.6 else (60, 60, 64, 255))
    rotor.line(2, 8, 13, 8, (190, 196, 204, 200))
    tool = plate((120, 90, 50) if scoop else (60, 64, 70), key + ":tool")
    if scoop:
        for x in range(SIZE):
            tool.set(x, 0, (180, 186, 194))
            tool.set(x, 15, (180, 186, 194))
    else:
        tool.hline(0, 15, 8, STEEL)
    return _strip([body, boom, rotor, tool])


FRONT_STYLES["planner"] = planner_face
ITEM_STYLES.update({
    "stake": item_stake,
    "heavy_drone_frame": item_heavy_drone_frame,
    "construction_drone": lambda base, key: _site_drone_icon(base, key, "gripper"),
    "terraformer": lambda base, key: _site_drone_icon(base, key, "scoop"),
})
