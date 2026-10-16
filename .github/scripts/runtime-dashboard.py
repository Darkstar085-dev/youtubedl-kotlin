#!/usr/bin/env python3
import json
import os
import sys
import time
from pathlib import Path

GROUPS = [
    ("Environment Preparation", "cyan", [
        ("clean-cache", "Clean package build cache"),
        ("sync-repo", "Sync repository"),
        ("refresh-index", "Refresh Termux package indexes"),
    ]),
    ("Check Latest Versions", "purple", [
        ("check-versions", "Check repository candidate versions"),
    ]),
    ("Install / Update Packages", "orange", [
        ("install-packages", "Reinstall required packages"),
    ]),
    ("Build Runtime Artifacts", "yellow", [
        ("build-env", "Prepare build environment"),
        ("build-native", "Build Python, PyCryptodomeX and QuickJS"),
        ("assemble-python", "Assemble Python runtime"),
        ("openssl-providers", "Bundle OpenSSL providers"),
        ("python-runtime", "Refresh Python runtime artifacts"),
        ("media-runtime", "Build FFmpeg and aria2 artifacts"),
        ("ytdlp", "Download and validate yt-dlp"),
    ]),
    ("Finalize & Update", "blue", [
        ("validate", "Validate runtime payload"),
        ("metadata", "Update version metadata"),
        ("commit", "Review and commit changes"),
        ("push", "Push and verify remote HEAD"),
    ]),
]
COLORS = {
    "cyan": "\033[96m", "purple": "\033[95m", "green": "\033[92m",
    "yellow": "\033[93m", "magenta": "\033[95m", "blue": "\033[94m",
    "red": "\033[91m", "orange": "\033[38;5;208m", "dim": "\033[2m", "white": "\033[97m",
    "reset": "\033[0m", "bold": "\033[1m",
}
ALL_TASKS = [(gid, task, label) for gid, (_, _, tasks) in enumerate(GROUPS) for task, label in tasks]

def read_state(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"started": time.time(), "current": None, "label": "Starting runtime builder", "status": "running", "tasks": {}, "exit_code": None}

def write_state(path, state):
    target = Path(path)
    temp = target.with_suffix(target.suffix + ".tmp")
    temp.write_text(json.dumps(state), encoding="utf-8")
    temp.replace(target)

def update(path, task, label):
    state = read_state(path)
    previous = state.get("current")
    if task and task != previous and task in [item[1] for item in ALL_TASKS]:
        if previous and state["tasks"].get(previous) == "running":
            state["tasks"][previous] = "done"
        target_index = next(index for index, item in enumerate(ALL_TASKS) if item[1] == task)
        for index, (_, task_id, _) in enumerate(ALL_TASKS):
            if index < target_index and state["tasks"].get(task_id, "pending") == "pending":
                state["tasks"][task_id] = "skipped"
        state["current"] = task
        state["tasks"][task] = "running"
    elif task and previous == task:
        state["tasks"][task] = "running"
    state["label"] = label
    state["status"] = "running"
    write_state(path, state)

def finish(path, code):
    state = read_state(path)
    current = state.get("current")
    if current and state["tasks"].get(current) == "running":
        state["tasks"][current] = "done" if int(code) == 0 else "failed"
    if int(code) == 0:
        for _, task, _ in ALL_TASKS:
            if state["tasks"].get(task, "pending") == "pending":
                state["tasks"][task] = "skipped"
        state["status"] = "complete"
        state["label"] = "Runtime preparation complete"
    else:
        state["status"] = "failed"
        state["label"] = "Build failed — inspect the log details below"
    state["exit_code"] = int(code)
    state["finished"] = time.time()
    write_state(path, state)

def color(name, value):
    return COLORS.get(name, "") + value + COLORS["reset"]

RGB = {
    "cyan": ((0, 220, 235), (20, 125, 255)),
    "purple": ((205, 0, 255), (255, 70, 175)),
    "orange": ((255, 135, 0), (255, 55, 65)),
    "yellow": ((255, 230, 0), (255, 145, 55)),
    "blue": ((40, 180, 255), (100, 75, 255)),
}
OVERALL_STOPS = ((0, 220, 235), (20, 125, 255), (175, 65, 255))

def interpolate(start, end, amount):
    return tuple(round(a + (b - a) * amount) for a, b in zip(start, end))

def gradient_color(stops, amount):
    if len(stops) == 2:
        return interpolate(stops[0], stops[1], amount)
    scaled = amount * (len(stops) - 1)
    index = min(len(stops) - 2, int(scaled))
    return interpolate(stops[index], stops[index + 1], scaled - index)

def rgb_color(rgb, value):
    return f"\033[38;2;{rgb[0]};{rgb[1]};{rgb[2]}m{value}{COLORS['reset']}"

def gradient_text(text, tint):
    stops = RGB.get(tint, RGB["cyan"])
    visible = [char for char in text]
    if len(visible) <= 1:
        return rgb_color(stops[0], text)
    return "".join(
        rgb_color(gradient_color(stops, index / (len(visible) - 1)), char)
        for index, char in enumerate(visible)
    )

def bar(percent, width=24, tint="green", stops=None):
    filled = max(0, min(width, int(width * percent / 100)))
    palette = stops or RGB.get(tint, RGB["cyan"])
    cells = []
    for index in range(filled):
        amount = index / max(1, width - 1)
        cells.append(rgb_color(gradient_color(palette, amount), "━"))
    cells.append(color("dim", "─" * (width - filled)))
    return "".join(cells)

def terminal_width():
    try:
        descriptor = os.open("/dev/tty", os.O_WRONLY)
        try:
            return max(44, min(80, os.get_terminal_size(descriptor).columns))
        finally:
            os.close(descriptor)
    except OSError:
        return 56

def render(state, logfile, frame, terminal_height=None):
    width = terminal_width()
    compact = terminal_height is not None and terminal_height < 32
    rule = "━" * max(24, width - 2)
    done = sum(1 for _, task, _ in ALL_TASKS if state["tasks"].get(task) in ("done", "skipped"))
    total = len(ALL_TASKS)
    percent = round(done * 100 / total) if total else 0
    elapsed = max(0, int((state.get("finished") or time.time()) - state.get("started", time.time())))
    elapsed_text = f"{elapsed // 60:02d}:{elapsed % 60:02d}"
    current = state.get("current")
    spinner = "⠋⠙⠹⠸⠼⠴⠦⠧"[frame % 8]
    lines = []
    lines.append(color("yellow", "⬡") + " " + color("cyan", "youtubedl-kotlin") + " " + color("blue", "Runtime Builder") + color("dim", "  ARM64 • Termux"))
    lines.append(color("dim", rule))
    if not compact:
        lines.append("Repo  " + color("white", os.environ.get("RUNTIME_REPOSITORY", "unknown repository")))
        lines.append("Branch " + color("white", "main") + "  •  Device " + color("white", "Termux (aarch64)"))
    overall_width = max(10, width - 35)
    lines.append(color("cyan", "⚡ Overall ") + "[" + bar(percent, overall_width, stops=OVERALL_STOPS) + "] " + color("cyan" if state["status"] != "failed" else "red", f"{percent:3d}%") + " " + color("yellow", f"◷ {elapsed_text}"))
    lines.append(color("dim", rule))
    for group_id, (group_name, tint, tasks) in enumerate(GROUPS):
        statuses = [state["tasks"].get(task, "pending") for task, _ in tasks]
        group_done = sum(1 for status in statuses if status in ("done", "skipped"))
        group_percent = round(group_done * 100 / len(tasks))
        active = current in [task for task, _ in tasks] and state["status"] == "running"
        group_mark = "▶" if active else ("✓" if group_percent == 100 else f"{group_id + 1}")
        if compact:
            summary = f"{group_mark}  {group_name}  {group_percent:3d}% {group_done}/{len(tasks)}"
            lines.append(gradient_text(summary, tint))
        else:
            lines.append(gradient_text(f"{group_mark}  {group_name}", tint))
            group_bar_width = max(8, min(20, width - 34))
            lines.append("   [" + bar(group_percent, group_bar_width, tint) + f"] {group_percent:3d}%  " + color("dim", f"{group_done}/{len(tasks)}"))
        if compact and not active:
            continue
        for task, default_label in tasks:
            status = state["tasks"].get(task, "pending")
            label = state.get("label", default_label) if task == current else default_label
            if status == "done":
                mark, status_text, status_color = "✓", "Done", "green"
            elif status == "skipped":
                mark, status_text, status_color = "↷", "Skipped", "dim"
            elif status == "failed":
                mark, status_text, status_color = "✗", "FAILED", "red"
            elif status == "running":
                mark, status_text, status_color = spinner, "Running", tint
            else:
                mark, status_text, status_color = "○", "Pending", "dim"
            label_width = max(12, width - 17)
            shown_label = label if len(label) <= label_width else label[:label_width - 1] + "…"
            status_width = 7
            row = " " + mark + " " + shown_label.ljust(label_width) + "  " + status_text.rjust(status_width)
            lines.append(color(status_color, row[:width - 1]))
    lines.append(color("dim", rule))
    if state["status"] == "failed":
        lines.append(color("red", "✗ BUILD FAILED") + "  " + state.get("label", "See log"))
        lines.append(color("dim", "Last log lines:"))
        try:
            tail = Path(logfile).read_text(encoding="utf-8", errors="replace").splitlines()[-5:]
            lines.extend(color("red", " " + line[-max(20, width - 4):]) for line in tail)
        except OSError:
            pass
    elif state["status"] == "complete":
        lines.append(color("green", "✓ BUILD COMPLETE") + "  " + state.get("label", "Done"))
        lines.append("Verbose log: " + color("cyan", str(logfile)))
    else:
        current_label = state.get("label", "Starting...")
        current_prefix = spinner + " CURRENT TASK  "
        current_available = max(12, width - len(current_prefix) - 2)
        if len(current_label) > current_available:
            current_label = current_label[:current_available - 1] + "…"
        lines.append(color("yellow", current_prefix) + color("white", current_label))
        lines.append(color("dim", "Detailed command output is being saved to the log file."))
    return "\n".join(lines)

def terminal_dimensions(terminal):
    try:
        size = os.get_terminal_size(terminal.fileno())
        return size.columns, size.lines
    except OSError:
        return terminal_width(), 24

def watch(path, logfile):
    try:
        terminal = open("/dev/tty", "w", encoding="utf-8", errors="replace")
    except OSError:
        return
    frame = 0
    previous_lines = None
    previous_size = None
    try:
        terminal.write("\033[?25l")
        while True:
            state = read_state(path)
            size = terminal_dimensions(terminal)
            resized = size != previous_size
            lines = render(state, logfile, frame, size[1]).splitlines()
            if previous_lines is None or resized:
                terminal.write("\033[2J\033[H")
                for index, line in enumerate(lines, start=1):
                    terminal.write(f"\033[{index};1H{line}\033[K")
                terminal.write(f"\033[{len(lines) + 1};1H\033[J")
            else:
                for index in range(max(len(previous_lines), len(lines))):
                    old_line = previous_lines[index] if index < len(previous_lines) else None
                    new_line = lines[index] if index < len(lines) else ""
                    if new_line != old_line:
                        terminal.write(f"\033[{index + 1};1H{new_line}\033[K")
                if len(lines) < len(previous_lines):
                    terminal.write(f"\033[{len(lines) + 1};1H\033[J")
            terminal.flush()
            previous_lines = lines
            previous_size = size
            frame += 1
            if state.get("status") in ("complete", "failed"):
                time.sleep(4)
                break
            time.sleep(0.25)
    finally:
        terminal.write("\033[?25h")
        terminal.flush()
        terminal.close()

def main():
    if len(sys.argv) < 3:
        raise SystemExit("usage: runtime-dashboard.py init|update|finish|watch STATE [ARGS]")
    action, state_path = sys.argv[1], sys.argv[2]
    if action == "init":
        completed_bootstrap = ("clean-cache", "sync-repo", "refresh-index", "check-versions", "install-packages")
        initial_tasks = {task: "done" for task in completed_bootstrap}
        write_state(state_path, {"started": time.time(), "current": None, "label": "Starting runtime builder", "status": "running", "tasks": initial_tasks, "exit_code": None})
    elif action == "update":
        update(state_path, sys.argv[3] if len(sys.argv) > 3 else "", sys.argv[4] if len(sys.argv) > 4 else "Working...")
    elif action == "finish":
        finish(state_path, sys.argv[3] if len(sys.argv) > 3 else "1")
    elif action == "watch":
        watch(state_path, sys.argv[3] if len(sys.argv) > 3 else "")
    else:
        raise SystemExit(f"Unknown action: {action}")

if __name__ == "__main__":
    main()
