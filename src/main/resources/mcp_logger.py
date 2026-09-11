#!/usr/bin/env python3

import sys
import subprocess
import threading
import os

# --- Configuration ---
LOG_FILE = os.path.join(os.path.dirname(os.path.realpath(__file__)), "mcp_io.log")
# --- End Configuration ---

# 清空/创建日志文件
with open(LOG_FILE, 'w', encoding='utf-8') as f:
    f.write("=== MCP Logger Started ===\n")

# 获取目标命令：忽略脚本名本身，把后面所有参数拼接为要执行的子进程命令
target_command = sys.argv[1:]

if not target_command:
    print("MCP Logger Error: No command provided.", file=sys.stderr)
    sys.exit(1)

# --- I/O Forwarding Functions ---

def forward_and_log_stdin(proxy_stdin, target_stdin, log_file):
    """Reads from proxy's stdin, logs it, writes to target's stdin."""
    try:
        while True:
            line_bytes = proxy_stdin.readline()
            if not line_bytes:
                break

            try:
                line_str = line_bytes.decode('utf-8', errors='replace')
            except Exception:
                line_str = f"[Non-UTF8 data, {len(line_bytes)} bytes]\n"

            log_file.write(f"输入: {line_str}")
            log_file.flush()

            target_stdin.write(line_bytes)
            target_stdin.flush()
    except Exception as e:
        try:
            log_file.write(f"!!! STDIN Forwarding Error: {e}\n")
            log_file.flush()
        except Exception: pass
    finally:
        try:
            target_stdin.close()
            log_file.write("--- STDIN stream closed to target ---\n")
            log_file.flush()
        except Exception: pass


def forward_and_log_stdout(target_stdout, proxy_stdout, log_file):
    """Reads from target's stdout, logs it, writes to proxy's stdout."""
    try:
        while True:
            line_bytes = target_stdout.readline()
            if not line_bytes:
                break

            try:
                line_str = line_bytes.decode('utf-8', errors='replace')
            except Exception:
                line_str = f"[Non-UTF8 data, {len(line_bytes)} bytes]\n"

            log_file.write(f"输出: {line_str}")
            log_file.flush()

            proxy_stdout.write(line_bytes)
            proxy_stdout.flush()
    except Exception as e:
        try:
            log_file.write(f"!!! STDOUT Forwarding Error: {e}\n")
            log_file.flush()
        except Exception: pass


def forward_and_log_stderr(target_stderr, proxy_stderr, log_file):
    """Reads from target's stderr, logs it, writes to proxy's stderr."""
    try:
        while True:
            line_bytes = target_stderr.readline()
            if not line_bytes:
                break

            try:
                line_str = line_bytes.decode('utf-8', errors='replace')
            except Exception:
                line_str = f"[Non-UTF8 data, {len(line_bytes)} bytes]\n"

            log_file.write(f"STDERR: {line_str}")
            log_file.flush()

            proxy_stderr.write(line_bytes)
            proxy_stderr.flush()
    except Exception as e:
        try:
            log_file.write(f"!!! STDERR Forwarding Error: {e}\n")
            log_file.flush()
        except Exception: pass


# --- Main Execution ---
process = None
log_f = None
exit_code = 1

try:
    log_f = open(LOG_FILE, 'a', encoding='utf-8')

    # 开启子进程
    process = subprocess.Popen(
        target_command,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0
    )

    stdin_thread = threading.Thread(
        target=forward_and_log_stdin,
        args=(sys.stdin.buffer, process.stdin, log_f),
        daemon=True
    )

    stdout_thread = threading.Thread(
        target=forward_and_log_stdout,
        args=(process.stdout, sys.stdout.buffer, log_f),
        daemon=True
    )

    stderr_thread = threading.Thread(
        target=forward_and_log_stderr,
        args=(process.stderr, sys.stderr.buffer, log_f),
        daemon=True
    )

    stdin_thread.start()
    stdout_thread.start()
    stderr_thread.start()

    process.wait()
    exit_code = process.returncode

    stdin_thread.join(timeout=1.0)
    stdout_thread.join(timeout=1.0)
    stderr_thread.join(timeout=1.0)

except Exception as e:
    print(f"MCP Logger Error: {e}", file=sys.stderr)
    if log_f and not log_f.closed:
        try:
            log_f.write(f"!!! MCP Logger Main Error: {e}\n")
            log_f.flush()
        except Exception: pass
    exit_code = 1

finally:
    if process and process.poll() is None:
        try:
            process.terminate()
            process.wait(timeout=1.0)
        except Exception: pass
        if process.poll() is None:
            try: process.kill()
            except Exception: pass

    if log_f and not log_f.closed:
        try:
            log_f.close()
        except Exception: pass

    sys.exit(exit_code)