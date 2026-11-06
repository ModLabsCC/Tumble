#!/usr/bin/env python3
"""Run an assembly conversion crash against a saved test world."""
import os
import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ['cmd', '/d', '/c', 'gradlew.bat'] if os.name == 'nt' else ['./gradlew']
MARKER = ROOT / 'build/run/assemblyCrash/assembly-crash-reached.txt'


def run(task, order):
    result = subprocess.run(
        [*GRADLE, task, '-PacceptMinecraftEula=true', f'-PassemblyCrashFlush={order}', '--console=plain'],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding='utf-8', errors='replace',
    )
    print(result.stdout, end='', flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--order', choices=('none', 'block', 'entity'), default='none')
    order = parser.parse_args().order
    if run('runAssemblyCrashBaseline', order).returncode != 0:
        raise RuntimeError('Crash baseline failed')
    crash = run('runAssemblyCrashInject', order)
    expected_marker = 'entity-load: ' if order == 'none' else f'{order}-save: '
    if (crash.returncode == 0 or 'finished with non-zero exit value 86' not in crash.stdout
            or not MARKER.is_file() or not MARKER.read_text(encoding='utf-8').startswith(expected_marker)):
        raise RuntimeError('Server did not reach the expected injected crash with exit 86')
    print(f'Injected crash at {MARKER.read_text(encoding="utf-8")}', flush=True)
    if run('runAssemblyCrashVerify', order).returncode != 0:
        raise RuntimeError('Crash recovery verification failed')
    print('Assembly crash test passed')


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
