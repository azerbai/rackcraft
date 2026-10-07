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
    return faces


def formed_face(base, key, on):
    """Casing for a formed multiblock: riveted panels with a hazard band and a window onto the core, so a whole cube
    reads as one big machine. The core glows and pulses while it runs."""
    frames = []
    for frame in range(4 if on else 1):
        canvas = plate(darken(base, 0.15), key + ":formed", rivets=True)
        canvas.frame(0, 0, 15, 15, darken(base, 0.55))
        for x in range(1, 15):
            canvas.set(x, 2, (226, 184, 72) if (x // 2) % 2 == 0 else BLACK)
            canvas.set(x, 13, (226, 184, 72) if (x // 2) % 2 == 0 else BLACK)
        canvas.inset(4, 5, 11, 10, GLASS_DARK, darken(base, 0.4), lighten(base, 0.2))
        if on:
            glow = mix((92, 236, 128), (190, 255, 210), [0.0, 0.4, 0.8, 0.4][frame])
            canvas.rect(5, 6, 10, 9, darken(glow, 0.25))
            canvas.rect(6, 7, 9, 8, glow)
        else:
            canvas.rect(6, 7, 9, 8, darken(base, 0.5))
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
