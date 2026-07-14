#!/usr/bin/env python3
"""
Renders a *simulated* MCP harness conversation as a terminal-styled GIF (see
demo/README.md). No real MCP client / server is driven — the transcript below is
scripted, but grounded in the actual compute-admin MCP tools and the gate invariant:
`list_machines` never exposes host/port/login (S9, spec-028); `add_recipe`/`add_action`
create PENDING_APPROVAL config; there is NO approve tool, so `run_action` on an
unapproved action is refused — approval is UI-only (GateArchTest). Edit CONVO to change
the script; re-run to regenerate.

Self-contained: Pillow renders frames, ffmpeg encodes the gif. No terminal / display.
    python3 demo/mcp-terminal.py --out demo/out/04-mcp-custom-scripts.gif
"""
import argparse, os, subprocess, tempfile, shutil
from PIL import Image, ImageDraw, ImageFont

W, H, PAD, TITLE_H, LH, FPS = 940, 660, 22, 36, 22, 18
MONO = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
MONO_B = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"
FONT = ImageFont.truetype(MONO, 15)
FONT_B = ImageFont.truetype(MONO_B, 15)
CW = FONT.getbbox("M")[2] - FONT.getbbox("M")[0]
COLS = (W - 2 * PAD) // CW
BODY_TOP = TITLE_H + 14
VIS = (H - BODY_TOP - PAD) // LH

C = {
    "bg": (13, 17, 23), "title": (22, 27, 34), "titletxt": (139, 148, 158),
    "prompt": (86, 211, 216), "user": (230, 237, 243), "assist": (173, 186, 199),
    "call": (227, 179, 65), "result": (126, 231, 135), "gate": (255, 123, 114),
    "meta": (110, 118, 129),
}

# kind -> (color, bold, prefix, chars-per-frame, pause-frames-after)
STYLE = {
    "prompt": ("prompt", True, "❯ ", 3, 8),
    "assist": ("assist", False, "  ", 3, 5),
    "call":   ("call", True, "→ ", 4, 4),
    "result": ("result", False, "    ", 10, 5),
    "gate":   ("gate", True, "⚠ ", 3, 8),
    "meta":   ("meta", False, "  ", 6, 6),
}

CONVO = [
    ("prompt", "Set up start/stop scripts for the app on api-prod-2."),
    ("assist", "I'll register them over MCP. First, what machines can I see?"),
    ("call", "list_machines()"),
    ("result", '[ { "id":"a0ba…", "name":"api-prod-2", "tags":["ubuntu"], "status":"ONLINE" },'),
    ("result", '  { "id":"83bf…", "name":"web-prod-1", "tags":["ubuntu"], "status":"ONLINE" } ]'),
    ("meta", "(host / port / login are never exposed over MCP — S9)"),
    ("assist", "Registering an `app-control` recipe on api-prod-2 with two custom actions."),
    ("call", 'add_recipe(machine="api-prod-2", name="app-control", type="CUSTOM")'),
    ("result", '{ "recipeId":"r-7f2a", "type":"CUSTOM" }'),
    ("call", 'add_action(recipe="r-7f2a", name="start", command="/opt/app/run.sh")'),
    ("result", '{ "actionId":"a-1c9d", "name":"start", "approvalState":"PENDING_APPROVAL" }'),
    ("call", 'add_action(recipe="r-7f2a", name="stop", command="/opt/app/kill.sh")'),
    ("result", '{ "actionId":"a-4e8b", "name":"stop", "approvalState":"PENDING_APPROVAL" }'),
    ("assist", "Registered — but not yet runnable. Let me try to start it:"),
    ("call", 'run_action(action="a-1c9d")'),
    ("gate", "refused: action is PENDING_APPROVAL — approval is UI-only,"),
    ("gate", "  and cannot be granted over MCP."),
    ("assist", "As expected — the gate holds. A human approves start & stop in the"),
    ("assist", "web UI (Review & approve); then run_action works."),
    ("meta", "✓ app-control · start=/opt/app/run.sh · stop=/opt/app/kill.sh — awaiting UI approval"),
]


def wrap(s):
    if not s:
        return [""]
    return [s[i:i + COLS] for i in range(0, len(s), COLS)]


def render(done, cur):
    """done: list of (colorkey,bold,text). cur: (colorkey,bold,text) partial or None."""
    img = Image.new("RGB", (W, H), C["bg"])
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, TITLE_H], fill=C["title"])
    for i, col in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
        d.ellipse([PAD + i * 20, 13, PAD + i * 20 + 11, 24], fill=col)
    d.text((PAD + 78, 10), "MCP harness  ▸  compute-admin", font=FONT, fill=C["titletxt"])

    rows = []  # (colorkey, bold, text, is_cursor_line)
    for ck, b, t in done:
        for j, seg in enumerate(wrap(t)):
            rows.append((ck, b, seg, False))
    if cur:
        ck, b, t = cur
        w = wrap(t)
        for j, seg in enumerate(w):
            rows.append((ck, b, seg, j == len(w) - 1))
    rows = rows[-VIS:]
    y = BODY_TOP
    for ck, b, t, cursor in rows:
        f = FONT_B if b else FONT
        d.text((PAD, y), t, font=f, fill=C[ck])
        if cursor:
            cx = PAD + len(t) * CW
            d.rectangle([cx, y + 2, cx + CW - 1, y + LH - 3], fill=(200, 200, 200))
        y += LH
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    tmp = tempfile.mkdtemp(prefix="mcp-term-")
    frames = []
    done = []
    for kind, text in CONVO:
        ck, bold, prefix, cps, pause = STYLE[kind]
        full = prefix + text
        shown = 0
        while shown < len(full):
            shown = min(len(full), shown + cps)
            frames.append(render(done, (ck, bold, full[:shown])))
        done.append((ck, bold, full))
        for _ in range(pause):
            frames.append(render(done, None))
    for _ in range(FPS * 2):  # hold the final frame ~2s
        frames.append(render(done, None))

    for i, fr in enumerate(frames):
        fr.save(os.path.join(tmp, f"f{i:05d}.png"))
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    pal = os.path.join(tmp, "pal.png")
    vf = f"fps={FPS}"
    subprocess.run(["ffmpeg", "-y", "-i", f"{tmp}/f%05d.png", "-vf", f"{vf},palettegen", pal], check=True)
    subprocess.run(["ffmpeg", "-y", "-framerate", str(FPS), "-i", f"{tmp}/f%05d.png", "-i", pal,
                    "-lavfi", f"{vf} [x];[x][1:v]paletteuse", args.out], check=True)
    shutil.rmtree(tmp, ignore_errors=True)
    print(f"wrote {args.out} ({len(frames)} frames)")


if __name__ == "__main__":
    main()
