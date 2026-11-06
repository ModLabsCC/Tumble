#!/usr/bin/env python3
"""Exercise linked-prop placement across real server crashes and restarts."""
import os
from pathlib import Path
import subprocess
import sys

sys.stdout.reconfigure(errors='backslashreplace')
sys.stderr.reconfigure(errors='backslashreplace')

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ['cmd', '/d', '/c', 'gradlew.bat'] if os.name == 'nt' else ['./gradlew']
RUN_DIR = ROOT / 'build/run/placementCrash'
CASES = [
    ('prepared', 'none'),
    ('beforeStarted', 'block'), ('beforeStarted', 'entity'),
    ('started', 'block'), ('started', 'entity'),
    ('removed', 'block'), ('removed', 'entity'),
]


def run(task, flags):
    result = subprocess.run([*GRADLE, task, '-PacceptMinecraftEula=true', *flags, '--console=plain'],
                            cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding='utf-8', errors='replace')
    if result.returncode and not any(f'finished with non-zero exit value {code}' in result.stdout for code in (86, 87)):
        print(result.stdout, end='', flush=True)
    return result


def main():
    for stage, flush in CASES:
        flags = [f'-PplacementCrashStage={stage}', f'-PplacementCrashFlush={flush}']
        marker = RUN_DIR / 'placement-crash-reached.txt'
        crash = run('runPlacementCrashInject', flags)
        if (crash.returncode == 0 or 'finished with non-zero exit value 86' not in crash.stdout
                or not marker.is_file() or marker.read_text(encoding='utf-8') != stage):
            raise RuntimeError(f'{stage}/{flush}: expected crash was not reached')
        if stage == 'started' and flush == 'entity':
            replay_marker = RUN_DIR / 'placement-replay-crash-reached.txt'
            replay_marker.unlink(missing_ok=True)
            replay = run('runPlacementCrashVerify', [*flags, '-PplacementReplayCrash=true'])
            if (replay.returncode == 0 or 'finished with non-zero exit value 87' not in replay.stdout
                    or not replay_marker.is_file()):
                raise RuntimeError('Interrupted placement replay did not reach its crash point')
        if run('runPlacementCrashVerify', flags).returncode != 0:
            raise RuntimeError(f'{stage}/{flush}: recovery verification failed')
        print(f'{stage}/{flush}: passed', flush=True)
    print('Placement crash matrix passed')


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
