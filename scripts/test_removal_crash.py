#!/usr/bin/env python3
"""Exercise durable removal across real server crashes and restarts."""
import os
from pathlib import Path
import subprocess
import sys

sys.stdout.reconfigure(errors='backslashreplace')
sys.stderr.reconfigure(errors='backslashreplace')

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ['cmd', '/d', '/c', 'gradlew.bat'] if os.name == 'nt' else ['./gradlew']
RUN_DIR = ROOT / 'build/run/removalCrash'
CASES = [
    ('prepared', 'none', 'api'),
    ('started', 'none', 'api'),
    ('removed', 'entity', 'api'),
    ('removed', 'block', 'primary'),
    ('started', 'entity', 'secondary'),
    ('removed', 'entity', 'clear'),
    ('removed', 'entity', 'direct'),
    ('checkpointed', 'none', 'tnt'),
    ('exploded', 'block', 'tnt'),
    ('connectionIo', 'none', 'clear'),
]


def run(task, flags, expected_connection_failure=False):
    result = subprocess.run([*GRADLE, task, '-PacceptMinecraftEula=true', *flags, '--console=plain'],
                            cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding='utf-8', errors='replace')
    expected = any(f'finished with non-zero exit value {code}' in result.stdout for code in (86, 87))
    expected |= expected_connection_failure and 'Could not persist connection removal' in result.stdout
    if result.returncode and not expected:
        print(result.stdout, end='', flush=True)
    return result


def main():
    for stage, flush, action in CASES:
        flags = [f'-PremovalCrashStage={stage}', f'-PremovalCrashFlush={flush}', f'-PremovalCrashAction={action}']
        marker = RUN_DIR / 'removal-crash-reached.txt'
        probe = stage == 'removed' and flush == 'entity' and action == 'api'
        probe_marker = RUN_DIR / 'removal-ticket-probe-reached.txt'
        if probe:
            probe_marker.unlink(missing_ok=True)
            flags.append('-PremovalTicketProbe=true')
        connection_failure = stage == 'connectionIo'
        crash = run('runRemovalCrashInject', flags, expected_connection_failure=connection_failure)
        expected_failure = ('Could not persist connection removal' in crash.stdout and
                            'Connected prop removal did not complete; journal retained' in crash.stdout
                            if connection_failure else 'finished with non-zero exit value 86' in crash.stdout)
        if (crash.returncode == 0 or not expected_failure or not marker.is_file()
                or marker.read_text(encoding='utf-8') != stage):
            raise RuntimeError(f'{stage}/{flush}/{action}: expected crash was not reached')
        if probe and (not probe_marker.is_file() or probe_marker.read_text(encoding='utf-8') != 'ticking and retained'):
            raise RuntimeError('Temporary removal ticket did not protect an actively ticking chunk')
        if stage == 'started' and flush == 'none' and action == 'api':
            replay_marker = RUN_DIR / 'removal-replay-crash-reached.txt'
            replay_marker.unlink(missing_ok=True)
            replay = run('runRemovalCrashVerify', [*flags, '-PremovalReplayCrash=true'])
            if (replay.returncode == 0 or 'finished with non-zero exit value 87' not in replay.stdout
                    or not replay_marker.is_file()):
                raise RuntimeError('Interrupted removal replay did not reach its crash point')
        if run('runRemovalCrashVerify', flags).returncode != 0:
            raise RuntimeError(f'{stage}/{flush}/{action}: recovery verification failed')
        print(f'{stage}/{flush}/{action}: passed', flush=True)
    print('Removal crash matrix passed')


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
