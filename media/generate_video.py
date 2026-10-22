#!/usr/bin/env python3
"""
media/generate_video.py

Generates an engaging, high-level, and visual 2-minute showcase video
in square format (1080x1080, 1:1 aspect ratio).

Editorial & Architectural Highlights:
  - 100% English narration with natural neural voice (Azure en-US-ChristopherNeural).
  - Implementation-agnostic: Abstract popular science style ("divulgativo").
    Focuses on distributed perception, modular pipelines, spatial decomposition,
    concurrency, and universal scalability laws without language or hardware jargon.
  - Duration: ~118 seconds (~2 minutes) across 7 dynamic visual acts.
  - High-definition procedural visual animations rendered at 1080x1080 via PyCairo.
  - Gentle ambient harmonic chord bed mixed underneath the studio voiceover.

Final Output: media/open_ai_cubes_showcase.mp4
"""

import os
import sys
import math
import wave
import struct
import shutil
import asyncio
import subprocess
import cairo

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
MEDIA_DIR = os.path.join(PROJECT_ROOT, "media")
AUDIO_DIR = os.path.join(MEDIA_DIR, "audio_neural")
FRAMES_DIR = os.path.join(MEDIA_DIR, "frames_sq")
OUTPUT_VIDEO = os.path.join(MEDIA_DIR, "open_ai_cubes_showcase.mp4")

WIDTH = 1080
HEIGHT = 1080
FPS = 30

# Elegant Dark Palette
C_BG = (0.05, 0.07, 0.12)
C_CARD_BG = (0.09, 0.13, 0.20)
C_BORDER = (0.18, 0.25, 0.38)
C_CYAN = (0.00, 0.82, 0.98)
C_AMBER = (1.00, 0.72, 0.15)
C_GREEN = (0.20, 0.88, 0.50)
C_ROSE = (0.98, 0.32, 0.45)
C_PURPLE = (0.62, 0.42, 0.92)
C_BLUE = (0.25, 0.55, 0.95)
C_TEXT_W = (0.96, 0.97, 0.99)
C_TEXT_M = (0.58, 0.65, 0.76)


def get_audio_duration(path: str) -> float:
    cmd = [
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        path
    ]
    return float(subprocess.check_output(cmd).decode().strip())


def generate_ambient_music(total_duration: float, out_path: str):
    sample_rate = 44100
    num_samples = int(sample_rate * total_duration)

    # Ambient progression: Cmaj9 -> Am9 -> Fmaj7 -> Gsus4
    chords = [
        [261.63, 329.63, 392.00, 493.88, 587.33],  # Cmaj9
        [220.00, 261.63, 329.63, 392.00, 440.00],  # Am9
        [174.61, 220.00, 261.63, 329.63, 392.00],  # Fmaj7
        [196.00, 261.63, 293.66, 392.00, 523.25]   # Gsus4
    ]

    with wave.open(out_path, 'w') as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(sample_rate)

        samples = []
        for i in range(num_samples):
            t = i / sample_rate
            chord_idx = int(t / 4.0) % len(chords)
            chord = chords[chord_idx]

            val = 0.0
            for freq in chord:
                val += 0.045 * math.sin(2 * math.pi * freq * t)
                val += 0.015 * math.sin(4 * math.pi * freq * t + 0.5)

            # Gentle rhythmic swell
            swell = 0.80 + 0.20 * math.sin(2 * math.pi * 0.25 * t)
            val *= swell

            # Soft fade-in and fade-out
            if t < 2.0:
                val *= (t / 2.0)
            elif t > total_duration - 2.5:
                val *= max(0.0, (total_duration - t) / 2.5)

            val = max(-1.0, min(1.0, val))
            samples.append(int(val * 32767.0))

        data = struct.pack('<' + 'h' * len(samples), *samples)
        f.writeframes(data)


async def synthesize_neural_voices():
    os.makedirs(AUDIO_DIR, exist_ok=True)

    # Implementation-agnostic script in English across 7 acts (~115-120s)
    chapters = [
        ("act1.wav", "Every second, the physical world bombards computing systems with an overwhelming flood of raw sensory signals. But how can a network of independent computing units collaborate to transform chaos into structured understanding?"),
        ("act2.wav", "It begins at the sensory boundary. Instead of processing the entire signal at once, localized spatial operators scan the data, isolating fundamental geometries: contrast boundaries, orientation vectors, and structural contours."),
        ("act3.wav", "Next, these high-dimensional maps undergo compression. By preserving only dominant activations across invariant spatial grids, millions of continuous values distill into a compact, universal feature signature."),
        ("act4.wav", "Here lies the beauty of modular design. Rather than relying on a monolithic engine, each perceptual stage operates as an autonomous unit. Intermediate representations flow forward like a relay race, handing off distilled knowledge with mathematical precision."),
        ("act5.wav", "When multiple queries surge into the ecosystem, concurrency becomes the lifeline. Specialized units replicate and divide the incoming tide, balancing the flow so no single stage becomes a bottleneck."),
        ("act6.wav", "Yet, scalability follows universal physical limits. As more units join the collective, throughput surges, until communication latency and coordination boundaries establish the natural theoretical ceiling."),
        ("act7.wav", "By decoupling perception into independent, collaborative modules, we build computing architectures that are resilient, scalable, and ready for the future of distributed intelligence.")
    ]

    durations = {}
    for name, text in chapters:
        mp3_path = os.path.join(AUDIO_DIR, name.replace(".wav", ".mp3"))
        wav_path = os.path.join(AUDIO_DIR, name)

        if os.path.exists(wav_path):
            dur = get_audio_duration(wav_path)
            durations[name] = dur
            print(f"  * Using existing {name}: {dur:.2f} s")
            continue

        import edge_tts
        # Generate using high-fidelity natural neural voice
        comm = edge_tts.Communicate(text, "en-US-ChristopherNeural", rate="-2%")
        await comm.save(mp3_path)

        # Convert to 44.1 kHz WAV with subtle presence EQ
        eq_filter = "aresample=44100,highpass=f=75,lowpass=f=10500,equalizer=f=200:t=q:w=1:g=1.5,equalizer=f=3200:t=q:w=1:g=2"
        subprocess.check_call([
            "ffmpeg", "-y", "-i", mp3_path,
            "-af", eq_filter, wav_path
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        dur = get_audio_duration(wav_path)
        durations[name] = dur
        print(f"  * Generated {name}: {dur:.2f} s")

    return durations


def draw_card(ctx, x, y, w, h, radius=16, border_color=C_BORDER, bg_color=C_CARD_BG, line_w=2.0):
    ctx.new_sub_path()
    ctx.arc(x + w - radius, y + radius, radius, -math.pi / 2, 0)
    ctx.arc(x + w - radius, y + h - radius, radius, 0, math.pi / 2)
    ctx.arc(x + radius, y + h - radius, radius, math.pi, 3 * math.pi / 2)
    ctx.arc(x + radius, y + radius, radius, math.pi, 3 * math.pi / 2)
    ctx.close_path()

    ctx.set_source_rgb(*bg_color)
    ctx.fill_preserve()
    ctx.set_source_rgb(*border_color)
    ctx.set_line_width(line_w)
    ctx.stroke()


def draw_header(ctx, act_num: int, act_title: str):
    # Top badge
    draw_card(ctx, 60, 45, 960, 80, radius=12, border_color=(0.15, 0.22, 0.35), bg_color=(0.08, 0.11, 0.18))
    
    ctx.select_font_face("Liberation Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(16)
    ctx.set_source_rgb(*C_CYAN)
    ctx.move_to(90, 80)
    ctx.show_text(f"ACT {act_num} OF 7")

    ctx.set_font_size(24)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(220, 92)
    ctx.show_text(act_title)

    ctx.set_font_size(15)
    ctx.set_source_rgb(*C_TEXT_M)
    ctx.move_to(800, 92)
    ctx.show_text("MODULAR PERCEPTION")


def render_act1(frame_idx: int, total_frames: int, out_path: str):
    """Act 1: The Perceptual Challenge — Sensory flood into structured understanding"""
    t = frame_idx / FPS
    progress = frame_idx / total_frames

    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, WIDTH, HEIGHT)
    ctx = cairo.Context(surface)
    ctx.set_source_rgb(*C_BG)
    ctx.paint()

    draw_header(ctx, 1, "The Perceptual Challenge")

    # Main Card
    draw_card(ctx, 60, 150, 960, 870, radius=20)

    # Subtitle
    ctx.select_font_face("Liberation Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(28)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(100, 215)
    ctx.show_text("Transforming Continuous Chaos into Structure")

    # Abstract signal stream: undulating wave lines
    num_waves = 7
    for w in range(num_waves):
        phase = t * 1.8 + w * 0.4
        freq = 0.008 + w * 0.002
        amp = 40 + w * 8
        base_y = 360 + w * 45

        ctx.set_line_width(2.2)
        r = 0.15 + 0.12 * math.sin(phase)
        g = 0.65 + 0.25 * math.cos(phase * 0.8)
        b = 0.90 + 0.10 * math.sin(phase * 1.2)
        ctx.set_source_rgba(r, g, b, 0.75)

        ctx.move_to(100, base_y)
        for x in range(100, 980, 10):
            y = base_y + amp * math.sin(x * freq + phase) * math.cos((x - 540) * 0.002)
            ctx.line_to(x, y)
        ctx.stroke()

    # Central organizing focal nodes
    center_x, center_y = 540, 770
    num_nodes = 8
    radius_ring = 140 + 15 * math.sin(t * 2.5)

    for i in range(num_nodes):
        angle = (2 * math.pi / num_nodes) * i + t * 0.5
        nx = center_x + radius_ring * math.cos(angle)
        ny = center_y + radius_ring * math.sin(angle)

        # Connection line to center
        ctx.set_line_width(1.5)
        ctx.set_source_rgba(0.0, 0.82, 0.98, 0.4)
        ctx.move_to(center_x, center_y)
        ctx.line_to(nx, ny)
        ctx.stroke()

        # Outer node
        ctx.arc(nx, ny, 14, 0, 2 * math.pi)
        ctx.set_source_rgb(*C_PURPLE)
        ctx.fill_preserve()
        ctx.set_source_rgb(*C_TEXT_W)
        ctx.set_line_width(2)
        ctx.stroke()

    # Core nucleus
    pulse = 32 + 6 * math.sin(t * 4.0)
    ctx.arc(center_x, center_y, pulse, 0, 2 * math.pi)
    ctx.set_source_rgba(0.0, 0.82, 0.98, 0.9)
    ctx.fill_preserve()
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.set_line_width(3)
    ctx.stroke()

    ctx.select_font_face("Liberation Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(18)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(440, 960)
    ctx.show_text("Independent Entities Harmonizing Input")

    surface.write_to_png(out_path)


def render_act2(frame_idx: int, total_frames: int, out_path: str):
    """Act 2: Spatial Feature Extraction — Localized operators discovering geometry"""
    t = frame_idx / FPS

    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, WIDTH, HEIGHT)
    ctx = cairo.Context(surface)
    ctx.set_source_rgb(*C_BG)
    ctx.paint()

    draw_header(ctx, 2, "Spatial Feature Extraction")
    draw_card(ctx, 60, 150, 960, 870, radius=20)

    ctx.select_font_face("Liberation Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(28)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(100, 215)
    ctx.show_text("Scanning Localized Receptive Fields")

    # Grid of sensory pixels (Left Card)
    draw_card(ctx, 100, 270, 410, 410, radius=12, bg_color=(0.07, 0.10, 0.16))
    grid_n = 10
    cell_s = 34
    start_x, start_y = 135, 305

    for row in range(grid_n):
        for col in range(grid_n):
            cx = start_x + col * cell_s
            cy = start_y + row * cell_s
            # Synthetic spatial gradient
            val = 0.5 + 0.45 * math.sin((col - 5) * 0.5 + t * 2) * math.cos((row - 5) * 0.4)
            ctx.set_source_rgb(val * 0.2, val * 0.6, val * 0.9)
            ctx.rectangle(cx, cy, cell_s - 3, cell_s - 3)
            ctx.fill()

    # Scanning operator window gliding across the grid
    scan_col = int((t * 2.5) % 7)
    scan_row = int((t * 1.5) % 7)
    win_x = start_x + scan_col * cell_s
    win_y = start_y + scan_row * cell_s
    ctx.set_source_rgba(1.0, 0.72, 0.15, 0.35)
    ctx.rectangle(win_x - 2, win_y - 2, cell_s * 3 + 1, cell_s * 3 + 1)
    ctx.fill()
    ctx.set_source_rgb(*C_AMBER)
    ctx.set_line_width(3)
    ctx.rectangle(win_x - 2, win_y - 2, cell_s * 3 + 1, cell_s * 3 + 1)
    ctx.stroke()

    # Right Card: Extracted Directional Gradients
    draw_card(ctx, 570, 270, 410, 410, radius=12, bg_color=(0.07, 0.10, 0.16))
    ctx.set_font_size(20)
    ctx.set_source_rgb(*C_CYAN)
    ctx.move_to(600, 315)
    ctx.show_text("Detected Structural Gradients")

    gradients = [
        ("Horizontal Contours", C_CYAN),
        ("Vertical Boundaries", C_GREEN),
        ("Diagonal Vectors", C_AMBER),
        ("Curvature Laplacian", C_ROSE)
    ]

    for idx, (label, col_rgb) in enumerate(gradients):
        gy = 370 + idx * 75
        draw_card(ctx, 600, gy, 350, 55, radius=8, bg_color=(0.11, 0.16, 0.25))

        # Animated mini-signal
        ctx.set_source_rgb(*col_rgb)
        ctx.set_line_width(2.5)
        ctx.move_to(620, gy + 28)
        for dx in range(0, 90, 5):
            ctx.line_to(620 + dx, gy + 28 + 12 * math.sin(dx * 0.15 + t * 4 + idx))
        ctx.stroke()

        ctx.set_font_size(16)
        ctx.set_source_rgb(*C_TEXT_W)
        ctx.move_to(730, gy + 34)
        ctx.show_text(label)

    # Bottom summary box
    draw_card(ctx, 100, 720, 880, 250, radius=14, bg_color=(0.08, 0.12, 0.19))
    ctx.set_font_size(22)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(140, 775)
    ctx.show_text("Key Principle: Receptive Field Locality")

    ctx.set_font_size(17)
    ctx.set_source_rgb(*C_TEXT_M)
    ctx.move_to(140, 820)
    ctx.show_text("By restricting operators to localized neighborhoods, computational complexity drops,")
    ctx.move_to(140, 855)
    ctx.show_text("while extracting translation-invariant structural primitives of the sensory world.")

    surface.write_to_png(out_path)


def render_act3(frame_idx: int, total_frames: int, out_path: str):
    """Act 3: Dimensional Abstraction — Preserving dominant invariant signals"""
    t = frame_idx / FPS

    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, WIDTH, HEIGHT)
    ctx = cairo.Context(surface)
    ctx.set_source_rgb(*C_BG)
    ctx.paint()

    draw_header(ctx, 3, "Dimensional Abstraction")
    draw_card(ctx, 60, 150, 960, 870, radius=20)

    ctx.select_font_face("Liberation Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(28)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(100, 215)
    ctx.show_text("Condensing Millions of Tokens into Signatures")

    # Flow from high-res matrix to dense condensed bar
    # 1. Left: High-dimensional matrix (ends at x = 430)
    draw_card(ctx, 90, 270, 340, 420, radius=12, bg_color=(0.07, 0.10, 0.16))
    ctx.set_font_size(18)
    ctx.set_source_rgb(*C_ROSE)
    ctx.move_to(115, 310)
    ctx.show_text("High-Dimensional Activations")

    for i in range(8):
        for j in range(8):
            bx = 115 + j * 36
            by = 340 + i * 40
            val = 0.3 + 0.6 * math.sin(i * 0.8 + j * 0.7 + t * 3)
            ctx.set_source_rgba(0.98, 0.32, 0.45, max(0.1, val))
            ctx.rectangle(bx, by, 30, 34)
            ctx.fill()

    # Flow arrow in center (between 430 and 650)
    ctx.set_source_rgb(*C_CYAN)
    ctx.set_line_width(4)
    ctx.move_to(465, 465)
    ctx.line_to(605, 465)
    ctx.stroke()
    ctx.move_to(590, 450)
    ctx.line_to(615, 465)
    ctx.line_to(590, 480)
    ctx.fill()

    # Dynamic particle traveling along arrow
    pkt_phase = (t * 2.5) % 1.0
    pkt_x = 465 + (605 - 465) * pkt_phase
    ctx.arc(pkt_x, 465, 7, 0, 2 * math.pi)
    ctx.set_source_rgb(*C_AMBER)
    ctx.fill()

    # Center Label: strictly centered at x=535, well clear of left card (430) and right card (650)
    ctx.select_font_face("Liberation Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(16)
    ctx.set_source_rgb(*C_CYAN)
    l1 = "Adaptive"
    l2 = "Max-Pooling"
    _, _, w1, h1, _, _ = ctx.text_extents(l1)
    ctx.move_to(535 - w1 / 2.0, 508)
    ctx.show_text(l1)
    _, _, w2, h2, _, _ = ctx.text_extents(l2)
    ctx.move_to(535 - w2 / 2.0, 532)
    ctx.show_text(l2)

    # 2. Right: Condensed Invariant Vector (starts at x = 650)
    draw_card(ctx, 650, 270, 340, 420, radius=12, bg_color=(0.07, 0.10, 0.16))
    ctx.set_font_size(18)
    ctx.set_source_rgb(*C_GREEN)
    ctx.move_to(675, 310)
    ctx.show_text("Distilled Invariant Vector")

    # Vertical spectrum bars
    num_bars = 16
    for b in range(num_bars):
        by = 340 + b * 20
        amp = 0.4 + 0.55 * math.sin(b * 0.6 + t * 2.5)
        bar_w = 260 * amp

        ctx.set_source_rgb(*C_GREEN)
        ctx.rectangle(675, by, bar_w, 14)
        ctx.fill()

    # Bottom summary box
    draw_card(ctx, 100, 730, 880, 240, radius=14, bg_color=(0.08, 0.12, 0.19))
    ctx.set_font_size(22)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(140, 785)
    ctx.show_text("Invariant Semantic Representation")

    ctx.set_font_size(17)
    ctx.set_source_rgb(*C_TEXT_M)
    ctx.move_to(140, 830)
    ctx.show_text("Spatial pooling discards noise and micro-jitter, preserving only maximal response peaks.")
    ctx.move_to(140, 865)
    ctx.show_text("The result is a compact mathematical fingerprint that is invariant to shifts and rotation.")

    surface.write_to_png(out_path)


def render_act4(frame_idx: int, total_frames: int, out_path: str):
    """Act 4: The Modular Pipeline — Independent autonomous stages in a relay"""
    t = frame_idx / FPS

    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, WIDTH, HEIGHT)
    ctx = cairo.Context(surface)
    ctx.set_source_rgb(*C_BG)
    ctx.paint()

    draw_header(ctx, 4, "The Modular Pipeline")
    draw_card(ctx, 60, 150, 960, 870, radius=20)

    ctx.select_font_face("Liberation Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(28)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(100, 215)
    ctx.show_text("Autonomous Stages in a Seamless Relay")

    # Three Modular Stage Boxes
    stages = [
        ("Input Tokenizer", C_BLUE, 100, 320),
        ("Spatial Extractor", C_AMBER, 410, 320),
        ("Decision Engine", C_PURPLE, 720, 320)
    ]

    for title, col_rgb, sx, sy in stages:
        draw_card(ctx, sx, sy, 260, 340, radius=14, border_color=col_rgb, bg_color=(0.08, 0.12, 0.19))
        ctx.set_font_size(18)
        ctx.set_source_rgb(*col_rgb)
        ctx.move_to(sx + 25, sy + 45)
        ctx.show_text(title)

        # Internal glowing core
        core_y = sy + 180
        pulse = 45 + 5 * math.sin(t * 3 + sx * 0.01)
        ctx.arc(sx + 130, core_y, pulse, 0, 2 * math.pi)
        ctx.set_source_rgba(col_rgb[0], col_rgb[1], col_rgb[2], 0.25)
        ctx.fill()
        ctx.arc(sx + 130, core_y, pulse * 0.6, 0, 2 * math.pi)
        ctx.set_source_rgb(*col_rgb)
        ctx.fill()

        ctx.set_font_size(14)
        ctx.set_source_rgb(*C_TEXT_M)
        ctx.move_to(sx + 35, sy + 300)
        ctx.show_text("Independent Entity")

    # Flowing data packets along conduits between boxes
    conduits = [(360, 410), (670, 720)]
    for start_x, end_x in conduits:
        ctx.set_source_rgb(*C_BORDER)
        ctx.set_line_width(6)
        ctx.move_to(start_x, 500)
        ctx.line_to(end_x, 500)
        ctx.stroke()

        # Flying packet
        phase = (t * 2.2 + start_x * 0.01) % 1.0
        pkt_x = start_x + (end_x - start_x) * phase
        ctx.arc(pkt_x, 500, 10, 0, 2 * math.pi)
        ctx.set_source_rgb(*C_CYAN)
        ctx.fill()

    # Bottom summary box
    draw_card(ctx, 100, 710, 880, 250, radius=14, bg_color=(0.08, 0.12, 0.19))
    ctx.set_font_size(22)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(140, 765)
    ctx.show_text("Pipelining: Decoupled Yet Harmonious")

    ctx.set_font_size(17)
    ctx.set_source_rgb(*C_TEXT_M)
    ctx.move_to(140, 810)
    ctx.show_text("No single module needs to know the internal implementation of another.")
    ctx.move_to(140, 845)
    ctx.show_text("A strictly defined mathematical interface guarantees total architectural decoupling,")
    ctx.move_to(140, 880)
    ctx.show_text("allowing each module to optimize its local resources independently.")

    surface.write_to_png(out_path)


def render_act5(frame_idx: int, total_frames: int, out_path: str):
    """Act 5: Concurrency and Load Balancing — Dividing incoming query torrents"""
    t = frame_idx / FPS

    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, WIDTH, HEIGHT)
    ctx = cairo.Context(surface)
    ctx.set_source_rgb(*C_BG)
    ctx.paint()

    draw_header(ctx, 5, "Concurrent Load Balancing")
    draw_card(ctx, 60, 150, 960, 870, radius=20)

    ctx.select_font_face("Liberation Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(28)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(100, 215)
    ctx.show_text("Dynamic Flow Routing without Bottlenecks")

    # Client query storm on left
    draw_card(ctx, 100, 270, 240, 420, radius=12, bg_color=(0.07, 0.10, 0.16))
    ctx.set_font_size(18)
    ctx.set_source_rgb(*C_AMBER)
    ctx.move_to(130, 310)
    ctx.show_text("Incoming Streams")

    for i in range(12):
        py = 350 + i * 26
        ctx.arc(140 + 10 * math.sin(t * 3 + i), py, 6, 0, 2 * math.pi)
        ctx.set_source_rgb(*C_AMBER)
        ctx.fill()
        ctx.move_to(160, py + 4)
        ctx.set_font_size(13)
        ctx.set_source_rgb(*C_TEXT_M)
        ctx.show_text(f"Query Task #{100+i}")

    # Central Load Balancer Dispatcher
    draw_card(ctx, 410, 360, 160, 240, radius=14, border_color=C_CYAN, bg_color=(0.10, 0.15, 0.25))
    ctx.set_font_size(16)
    ctx.set_source_rgb(*C_CYAN)
    ctx.move_to(430, 400)
    ctx.show_text("Dispatcher")

    # Spinning dispatch wheel
    rot = t * 3.0
    ctx.save()
    ctx.translate(490, 480)
    ctx.rotate(rot)
    ctx.set_source_rgb(*C_CYAN)
    ctx.set_line_width(4)
    ctx.arc(0, 0, 45, 0, 2 * math.pi)
    ctx.stroke()
    ctx.move_to(0, -45)
    ctx.line_to(0, 45)
    ctx.move_to(-45, 0)
    ctx.line_to(45, 0)
    ctx.stroke()
    ctx.restore()

    # Replicated worker units on right
    workers = [
        ("Worker Unit Alpha", C_GREEN, 650, 270),
        ("Worker Unit Beta", C_GREEN, 650, 380),
        ("Worker Unit Gamma", C_GREEN, 650, 490),
        ("Worker Unit Delta", C_GREEN, 650, 600)
    ]

    for label, col_rgb, wx, wy in workers:
        draw_card(ctx, wx, wy, 330, 85, radius=10, border_color=col_rgb, bg_color=(0.08, 0.12, 0.19))
        ctx.set_font_size(16)
        ctx.set_source_rgb(*col_rgb)
        ctx.move_to(wx + 20, wy + 35)
        ctx.show_text(label)

        # Dynamic throughput meter
        meter_val = 0.6 + 0.35 * math.sin(t * 4 + wy * 0.05)
        ctx.set_source_rgb(0.15, 0.22, 0.32)
        ctx.rectangle(wx + 20, wy + 50, 290, 14)
        ctx.fill()
        ctx.set_source_rgb(*col_rgb)
        ctx.rectangle(wx + 20, wy + 50, 290 * meter_val, 14)
        ctx.fill()

    # Bottom summary box
    draw_card(ctx, 100, 725, 880, 240, radius=14, bg_color=(0.08, 0.12, 0.19))
    ctx.set_font_size(22)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(140, 775)
    ctx.show_text("Dynamic Load Distribution")

    ctx.set_font_size(17)
    ctx.set_source_rgb(*C_TEXT_M)
    ctx.move_to(140, 820)
    ctx.show_text("By fanning out incoming tasks across identical replicated nodes,")
    ctx.move_to(140, 855)
    ctx.show_text("the system scales aggregate throughput while keeping response latencies predictable.")

    surface.write_to_png(out_path)


def render_act6(frame_idx: int, total_frames: int, out_path: str):
    """Act 6: Scalability Dynamics — Physical limits and Amdahl's Law"""
    t = frame_idx / FPS

    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, WIDTH, HEIGHT)
    ctx = cairo.Context(surface)
    ctx.set_source_rgb(*C_BG)
    ctx.paint()

    draw_header(ctx, 6, "Scalability Dynamics")
    draw_card(ctx, 60, 150, 960, 870, radius=20)

    ctx.select_font_face("Liberation Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(28)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(100, 215)
    ctx.show_text("Universal Laws of Concurrent Acceleration")

    # Scalability Chart Card
    draw_card(ctx, 100, 270, 880, 430, radius=14, bg_color=(0.07, 0.10, 0.16))

    # Coordinate Axes
    ox, oy = 210, 630
    w_axis = 690
    h_axis = 300

    ctx.set_source_rgb(*C_BORDER)
    ctx.set_line_width(2.5)
    ctx.move_to(ox, oy)
    ctx.line_to(ox + w_axis, oy)
    ctx.stroke()
    ctx.move_to(ox, oy)
    ctx.line_to(ox, oy - h_axis)
    ctx.stroke()

    # Y-axis ticks and values (1x, 2x, 3x, 4x, 5x)
    ctx.select_font_face("Liberation Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(14)
    for y_val in [1, 2, 3, 4, 5]:
        y_pos = oy - (y_val / 5.0) * h_axis
        ctx.set_source_rgb(*C_BORDER)
        ctx.set_line_width(1.5)
        ctx.move_to(ox - 6, y_pos)
        ctx.line_to(ox + w_axis, y_pos)
        ctx.stroke()

        txt = f"{y_val}x"
        ctx.set_source_rgb(*C_TEXT_M)
        _, _, tw, th, _, _ = ctx.text_extents(txt)
        ctx.move_to(ox - tw - 12, y_pos + th / 2.0)
        ctx.show_text(txt)

    # X-axis ticks and values (1, 2, 3, 4, 5, 6, 7, 8)
    for x_val in range(1, 9):
        x_pos = ox + (x_val / 8.0) * w_axis
        ctx.set_source_rgb(*C_BORDER)
        ctx.set_line_width(1.5)
        ctx.move_to(x_pos, oy)
        ctx.line_to(x_pos, oy + 6)
        ctx.stroke()

        txt = str(x_val)
        ctx.set_source_rgb(*C_TEXT_M)
        _, _, tw, th, _, _ = ctx.text_extents(txt)
        ctx.move_to(x_pos - tw / 2.0, oy + 24)
        ctx.show_text(txt)

    # X Axis Label (Centered cleanly under the X axis)
    ctx.set_font_size(16)
    ctx.set_source_rgb(*C_TEXT_W)
    x_label = "Number of Active Computing Units (K)"
    _, _, w_xl, _, _, _ = ctx.text_extents(x_label)
    ctx.move_to(ox + (w_axis - w_xl) / 2.0, oy + 54)
    ctx.show_text(x_label)

    # Y Axis Label (Rotated and positioned safely to the left of the axis)
    y_label = "Aggregate Speedup S(K)"
    ctx.set_font_size(16)
    ctx.set_source_rgb(*C_TEXT_W)
    _, _, w_yl, _, _, _ = ctx.text_extents(y_label)

    ctx.save()
    ctx.translate(ox - 58, oy - (h_axis - w_yl) / 2.0)
    ctx.rotate(-math.pi / 2)
    ctx.move_to(0, 0)
    ctx.show_text(y_label)
    ctx.restore()

    # Plot Ideal Linear Speedup (dashed)
    ctx.set_source_rgba(0.4, 0.5, 0.65, 0.5)
    ctx.set_dash([6, 6])
    ctx.move_to(ox, oy)
    ctx.line_to(ox + w_axis * 0.8, oy - h_axis * 0.8)
    ctx.stroke()
    ctx.set_dash([])

    # Plot Amdahl's Law Asymptotic Curve (cyan)
    ctx.set_source_rgb(*C_CYAN)
    ctx.set_line_width(4)
    ctx.move_to(ox, oy)
    p = 0.88  # 88% parallel fraction
    for step in range(1, 101):
        k_val = 1.0 + (step / 100.0) * 7.0
        speedup = 1.0 / ((1.0 - p) + (p / k_val))
        x_pt = ox + (k_val / 8.0) * w_axis
        y_pt = oy - (speedup / 5.0) * h_axis
        ctx.line_to(x_pt, y_pt)
    ctx.stroke()

    # Dynamic point tracking along curve
    anim_k = 1.0 + ((t * 0.8) % 1.0) * 6.5
    anim_s = 1.0 / ((1.0 - p) + (p / anim_k))
    pt_x = ox + (anim_k / 8.0) * w_axis
    pt_y = oy - (anim_s / 5.0) * h_axis

    ctx.arc(pt_x, pt_y, 10, 0, 2 * math.pi)
    ctx.set_source_rgb(*C_AMBER)
    ctx.fill_preserve()
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.set_line_width(2.5)
    ctx.stroke()

    # Asymptotic ceiling line
    asymp_y = oy - ((1.0 / (1.0 - p)) / 5.0) * h_axis
    ctx.set_source_rgba(0.98, 0.32, 0.45, 0.6)
    ctx.set_dash([4, 4])
    ctx.move_to(ox, asymp_y)
    ctx.line_to(ox + w_axis, asymp_y)
    ctx.stroke()
    ctx.set_dash([])

    ctx.set_font_size(14)
    ctx.set_source_rgb(*C_ROSE)
    ctx.move_to(ox + 420, asymp_y - 12)
    ctx.show_text("Theoretical Ceiling: Communication & Serial Limits")

    # Bottom summary box
    draw_card(ctx, 100, 730, 880, 240, radius=14, bg_color=(0.08, 0.12, 0.19))
    ctx.set_font_size(22)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(140, 785)
    ctx.show_text("The Boundary of Concurrent Acceleration")

    ctx.set_font_size(17)
    ctx.set_source_rgb(*C_TEXT_M)
    ctx.move_to(140, 830)
    ctx.show_text("Adding parallel units yields high returns until the serialized coordination fraction")
    ctx.move_to(140, 865)
    ctx.show_text("and network transit costs dominate, dictating the optimal architectural operating point.")

    surface.write_to_png(out_path)


def render_act7(frame_idx: int, total_frames: int, out_path: str):
    """Act 7: The Future of Modular Perception — Decentralized, resilient intelligence"""
    t = frame_idx / FPS

    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, WIDTH, HEIGHT)
    ctx = cairo.Context(surface)
    ctx.set_source_rgb(*C_BG)
    ctx.paint()

    draw_header(ctx, 7, "The Future of Perception")
    draw_card(ctx, 60, 150, 960, 870, radius=20)

    ctx.select_font_face("Liberation Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(28)
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.move_to(100, 215)
    ctx.show_text("Decentralized, Scalable Intelligent Perception")

    # Interconnected constellation of glowing nodes
    center_x, center_y = 540, 520
    num_satellites = 12

    for ring_r in [110, 220]:
        ctx.set_source_rgba(0.2, 0.3, 0.45, 0.3)
        ctx.set_line_width(1.5)
        ctx.arc(center_x, center_y, ring_r, 0, 2 * math.pi)
        ctx.stroke()

    for s in range(num_satellites):
        ang = (2 * math.pi / num_satellites) * s + t * 0.35
        dist = 180 + 40 * math.sin(s * 1.5 + t * 2)
        sx = center_x + dist * math.cos(ang)
        sy = center_y + dist * math.sin(ang)

        # Connection line
        ctx.set_source_rgba(0.0, 0.82, 0.98, 0.4)
        ctx.set_line_width(1.5)
        ctx.move_to(center_x, center_y)
        ctx.line_to(sx, sy)
        ctx.stroke()

        # Satellite node
        col = C_CYAN if s % 2 == 0 else C_PURPLE
        ctx.arc(sx, sy, 12, 0, 2 * math.pi)
        ctx.set_source_rgb(*col)
        ctx.fill_preserve()
        ctx.set_source_rgb(*C_TEXT_W)
        ctx.set_line_width(2)
        ctx.stroke()

    # Core nucleus pulsing
    pulse = 42 + 8 * math.sin(t * 3.5)
    ctx.arc(center_x, center_y, pulse, 0, 2 * math.pi)
    ctx.set_source_rgba(0.20, 0.88, 0.50, 0.85)
    ctx.fill_preserve()
    ctx.set_source_rgb(*C_TEXT_W)
    ctx.set_line_width(3.5)
    ctx.stroke()

    # Bottom summary box
    draw_card(ctx, 100, 770, 880, 200, radius=14, bg_color=(0.08, 0.12, 0.19))
    ctx.set_font_size(24)
    ctx.set_source_rgb(*C_CYAN)
    ctx.move_to(140, 825)
    ctx.show_text("Modular Perception: Resilient, Scalable, Distributed")

    ctx.set_font_size(17)
    ctx.set_source_rgb(*C_TEXT_M)
    ctx.move_to(140, 875)
    ctx.show_text("A blueprint for next-generation distributed artificial intelligence.")

    surface.write_to_png(out_path)


RENDERERS = {
    "act1": render_act1,
    "act2": render_act2,
    "act3": render_act3,
    "act4": render_act4,
    "act5": render_act5,
    "act6": render_act6,
    "act7": render_act7,
}


def build_act_video(act_key: str, audio_path: str, out_video_path: str):
    dur = get_audio_duration(audio_path)
    total_frames = int(dur * FPS) + 30  # add 1s breathing room

    act_frames_dir = os.path.join(FRAMES_DIR, act_key)
    if os.path.exists(act_frames_dir):
        shutil.rmtree(act_frames_dir)
    os.makedirs(act_frames_dir, exist_ok=True)

    renderer = RENDERERS[act_key]
    print(f"  -> {act_key}: {total_frames} frames ({total_frames / FPS:.2f} s)...", end="", flush=True)

    for f in range(total_frames):
        frame_file = os.path.join(act_frames_dir, f"frame_{f:05d}.png")
        renderer(f, total_frames, frame_file)

    # Encode act video
    raw_video = os.path.join(FRAMES_DIR, f"raw_{act_key}.mp4")
    frame_pattern = os.path.join(act_frames_dir, "frame_%05d.png")
    subprocess.check_call([
        "ffmpeg", "-y", "-framerate", str(FPS),
        "-i", frame_pattern,
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18",
        "-r", str(FPS), raw_video
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # Multiplex voiceover audio
    subprocess.check_call([
        "ffmpeg", "-y", "-i", raw_video, "-i", audio_path,
        "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
        "-shortest", out_video_path
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # Clean intermediate files
    shutil.rmtree(act_frames_dir)
    try: os.remove(raw_video)
    except Exception: pass
    print(" [OK]")


def main():
    print("=" * 80)
    print("   NATURAL NEURAL SHOWCASE VIDEO GENERATOR (1080x1080) — 2 MINUTES")
    print("=" * 80)

    # Step 1: Synthesize English voiceover with natural neural voice
    print("\n[Step 1/5] Synthesizing natural English voiceover (en-US-ChristopherNeural)...")
    durations = asyncio.run(synthesize_neural_voices())
    total_voice_dur = sum(durations.values())
    print(f"  * Total speech duration: {total_voice_dur:.2f} s")

    # Step 2: Generate harmonic ambient music
    print("\n[Step 2/5] Generating procedural ambient chord bed...")
    music_path = os.path.join(AUDIO_DIR, "ambient_bed.wav")
    total_est_dur = total_voice_dur + 7 * 1.0 + 5.0
    generate_ambient_music(total_est_dur, music_path)
    print("  [OK] Ambient music generated.")

    # Step 3: Render and encode each act
    print("\n[Step 3/5] Rendering 1080x1080 visual frames and acts at 30 FPS...")
    os.makedirs(FRAMES_DIR, exist_ok=True)
    act_videos = []

    for i in range(1, 8):
        act_key = f"act{i}"
        audio_file = os.path.join(AUDIO_DIR, f"{act_key}.wav")
        act_out = os.path.join(FRAMES_DIR, f"{act_key}.mp4")
        build_act_video(act_key, audio_file, act_out)
        act_videos.append(act_out)

    # Step 4: Concatenate acts
    print("\n[Step 4/5] Concatenating acts...")
    concat_list = os.path.join(FRAMES_DIR, "concat_list.txt")
    with open(concat_list, "w") as f:
        for v in act_videos:
            f.write(f"file '{v}'\n")

    combined_raw = os.path.join(FRAMES_DIR, "combined_raw.mp4")
    subprocess.check_call([
        "ffmpeg", "-y", "-f", "concat", "-safe", "0",
        "-i", concat_list,
        "-c", "copy", combined_raw
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # Step 5: Mix in subtle ambient music bed and finalize output
    print("\n[Step 5/5] Mixing ambient soundtrack and finalizing showcase video...")
    subprocess.check_call([
        "ffmpeg", "-y",
        "-i", combined_raw,
        "-i", music_path,
        "-filter_complex",
        "[0:a]volume=1.0[v];[1:a]volume=0.18[m];[v][m]amix=inputs=2:duration=first[aout]",
        "-map", "0:v", "-map", "[aout]",
        "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
        OUTPUT_VIDEO
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # Final cleanup of temp frames dir
    shutil.rmtree(FRAMES_DIR)

    final_dur = get_audio_duration(OUTPUT_VIDEO)
    file_size_mb = os.path.getsize(OUTPUT_VIDEO) / (1024 * 1024)

    print("\n" + "=" * 80)
    print(f" [SUCCESS] Video generated: {OUTPUT_VIDEO}")
    print(f"   * Exact Duration: {final_dur:.2f} seconds ({final_dur / 60:.2f} minutes)")
    print(f"   * File Size: {file_size_mb:.2f} MB")
    print(f"   * Resolution: 1080x1080 (1:1 Square)")
    print(f"   * Audio: Natural Neural Voice (en-US-ChristopherNeural) + Ambient Bed")
    print("=" * 80)


if __name__ == "__main__":
    main()
