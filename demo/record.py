#!/usr/bin/env python3
"""
Standalone GIF recorder for the compute-admin demo (see demo/README.md).

This is the FALLBACK driver. The preferred path is the `test-flow-headless` agent,
which drives the same Firefox+geckodriver stack from the plain-language steps in
demo/steps.md. Use this script when you want a scripted, repeatable capture without
the agent.

It does the plumbing that is stable regardless of the app:
  - launch headless Firefox at a chosen viewport (desktop / mobile),
  - capture screenshots on a timer into frames/, while a flow function drives the UI,
  - encode the frames to an optimized GIF via ffmpeg (palettegen + paletteuse).

What it does NOT do yet: the per-flow click sequences. Those live as stub functions
(flow_1/2/3) whose bodies must be filled from demo/steps.md — parsing markdown into
Selenium calls is intentionally out of scope, and keeping them as explicit Python is
more maintainable. Each stub is annotated with the matching steps.md section.

Requirements: selenium, firefox, geckodriver, ffmpeg on PATH.
    python3 demo/record.py --section 1 --viewport mobile --out demo/out/01-....mobile.gif
"""
import argparse, os, shutil, subprocess, tempfile, threading, time

VIEWPORTS = {"desktop": (1280, 800), "mobile": (390, 844)}  # mobile < spec-043 --bp-sm (480)
BASE_URL = os.environ.get("DEMO_BASE_URL", "http://localhost:8099")
DEMO_USER = ("demo@example.com", "demo-pass")  # keep in sync with demo/fake-fleet.md
FPS = 8  # capture cadence; low fps keeps the gif small and readable


class Recorder:
    """Timer-thread screenshot capture → ffmpeg gif."""
    def __init__(self, driver, out_path):
        self.driver, self.out = driver, out_path
        self.dir = tempfile.mkdtemp(prefix="ca-demo-frames-")
        self._stop = threading.Event()
        self._n = 0
        self._t = None

    def _loop(self):
        interval = 1.0 / FPS
        while not self._stop.is_set():
            try:
                self.driver.save_screenshot(os.path.join(self.dir, f"f{self._n:05d}.png"))
                self._n += 1
            except Exception:
                pass
            time.sleep(interval)

    def start(self):
        self._t = threading.Thread(target=self._loop, daemon=True); self._t.start()

    def stop_and_encode(self):
        self._stop.set(); self._t.join(timeout=2)
        os.makedirs(os.path.dirname(self.out) or ".", exist_ok=True)
        palette = os.path.join(self.dir, "pal.png")
        vf = f"fps={FPS},scale=iw:-1:flags=lanczos"
        subprocess.run(["ffmpeg", "-y", "-i", f"{self.dir}/f%05d.png",
                        "-vf", f"{vf},palettegen", palette], check=True)
        subprocess.run(["ffmpeg", "-y", "-framerate", str(FPS), "-i", f"{self.dir}/f%05d.png",
                        "-i", palette, "-lavfi", f"{vf} [x];[x][1:v]paletteuse",
                        self.out], check=True)
        shutil.rmtree(self.dir, ignore_errors=True)
        print(f"wrote {self.out}")


def make_driver(viewport):
    from selenium import webdriver
    from selenium.webdriver.firefox.options import Options
    w, h = VIEWPORTS[viewport]
    opts = Options()
    opts.add_argument("--headless")
    opts.add_argument(f"--width={w}"); opts.add_argument(f"--height={h}")
    d = webdriver.Firefox(options=opts)
    d.set_window_size(w, h)
    return d


def login(d):
    """Log in as the demo user. Update selectors if the login screen changes."""
    from selenium.webdriver.common.by import By
    d.get(BASE_URL)
    time.sleep(1.0)
    # TODO(steps.md header): fill email/password inputs and submit. The login form
    # fields are the only inputs on the pre-shell screen; adjust if that changes.
    raise NotImplementedError("fill from the current login screen")


def is_mobile(d):
    return d.get_window_size()["width"] < 480


def open_menu_if_mobile(d):
    """spec-043: on mobile, open the collapsed nav via the Menu toggle before navigating."""
    if is_mobile(d):
        from selenium.webdriver.common.by import By
        for b in d.find_elements(By.CSS_SELECTOR, "#nav-toggle, .nav-toggle"):
            if b.is_displayed():
                b.click(); time.sleep(0.4); return


# ---- flow bodies: fill each from the matching demo/steps.md section ----
def flow_1(d):  # steps.md §1 — add machine + discover
    raise NotImplementedError("implement from steps.md §1")

def flow_2(d):  # steps.md §2 — enable monitor recipes/actions
    raise NotImplementedError("implement from steps.md §2")

def flow_3(d):  # steps.md §3 — monitor fleet view
    raise NotImplementedError("implement from steps.md §3")

FLOWS = {1: flow_1, 2: flow_2, 3: flow_3}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--section", type=int, required=True, choices=[1, 2, 3])
    ap.add_argument("--viewport", choices=list(VIEWPORTS), default="desktop")
    ap.add_argument("--out", required=True)
    ap.add_argument("--steps", default="demo/steps.md", help="reference only; flows are coded here")
    args = ap.parse_args()

    d = make_driver(args.viewport)
    rec = Recorder(d, args.out)
    try:
        login(d)
        rec.start()
        FLOWS[args.section](d)
        time.sleep(2.5)  # hold the final frame
    finally:
        rec.stop_and_encode()
        d.quit()


if __name__ == "__main__":
    main()
