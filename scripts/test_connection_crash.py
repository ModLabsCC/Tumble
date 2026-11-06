#!/usr/bin/env python3
"""Crash after a durable connection intent, then restart the same server world."""
import os
from pathlib import Path
import subprocess
import sys

sys.stdout.reconfigure(errors='backslashreplace')
sys.stderr.reconfigure(errors='backslashreplace')

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ['cmd', '/d', '/c', 'gradlew.bat'] if os.name == 'nt' else ['./gradlew']
MARKER = ROOT / 'build/run/connectionCrash/connection-crash-reached.txt'


def run(task):
    result = subprocess.run([*GRADLE, task, '-PacceptMinecraftEula=true', '--console=plain'], cwd=ROOT,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    print(result.stdout, end='', flush=True)
    return result


def main():
    crash = run('runConnectionCrashInject')
    if (crash.returncode == 0 or 'finished with non-zero exit value 87' not in crash.stdout
            or not MARKER.is_file() or MARKER.read_text(encoding='utf-8') != 'intent'):
        raise RuntimeError('Server did not reach the expected connection-intent crash')
    if run('runConnectionCrashVerify').returncode != 0:
        raise RuntimeError('Connection crash recovery verification failed')
    print('Connection crash test passed')


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
