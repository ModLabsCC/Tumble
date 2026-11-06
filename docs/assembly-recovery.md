# Assembly conversion recovery

Tumble writes a journal before converting an assembly and checkpoints the level before removing the journal. Recovery runs before normal server ticks. A missing dimension, unreadable journal, chunk I/O error, unexpected entity, or conflicting source block stops recovery and retains the journal.

If startup stops during recovery:

1. Stop the server and copy the entire world directory, including `tumble-assembly-journal`, before editing anything.
2. Find the conversion UUID in the server log and inspect its `<UUID>.journal` file. The file has a 16-byte binary header followed by UTF-8 JSON. For example, in PowerShell:

   ```powershell
   $bytes = [System.IO.File]::ReadAllBytes('PATH_TO_JOURNAL')
   $length = [System.Net.IPAddress]::NetworkToHostOrder([System.BitConverter]::ToInt32($bytes, 4))
   [System.Text.Encoding]::UTF8.GetString($bytes, 16, $length) | ConvertFrom-Json | ConvertTo-Json -Depth 20
   ```

   The record contains the dimension, origin, block-state palette, and offsets. Do not edit the journal: its contents are checksummed. If it is corrupt, restore the world and journal together from a backup.
3. Check the affected positions and assembly entity in an offline world editor. Relocate unexpected blocks without discarding their contents. For an older record with `fenced` absent or false, matching blocks may also have been placed independently; determine their provenance before changing them. If `passengerSafe` is absent or false, a missing assembly may be saved under an unloaded vehicle. Locate it and any passengers, safely dismount them, and return the assembly to its recorded origin before replay. If it cannot be located, keep the journal and restore a consistent backup or determine a manual recovery from the backed-up data. Once the source positions are known to be safe to clear, use the editor to leave them as air without creating drops; replay will restore the recorded blocks and remove the captured entity. A pending assembly moved to another chunk or dimension by another mod must also be located before restoring its source.
4. Restart and let Tumble replay the retained journal. Never delete a retained journal while the provenance of its blocks and entity is unresolved. Keep the backup until the expected blocks and entity state have been verified.

The journal and checkpoint target abrupt server-process termination on a local filesystem. Directory-entry durability after an operating-system crash or power loss is not guaranteed. A conversion performs a synchronous level save, which can briefly pause a large server. A pending assembly cannot ride another entity or carry passengers until its checkpoint completes. Other mods that directly modify chunk internals or teleport a pending assembly can bypass the normal recovery assumptions.

If the server reports any chunk or entity storage I/O failure, Tumble refuses new assembly conversions for the rest of that server session. Investigate the reported storage error and restart after correcting it; a failed load may otherwise cause a conversion to capture substituted or incomplete world data. Other conversion paths are unaffected by this guard.


## Connected props

Tumble keeps connection records and coherent component checkpoints in the world's `data/tumble_connections.dat`. Back up this file with the rest of the world. A linked component stays parked until all of its recorded entities and terrain are available; a corrupt journal stops loading rather than silently dropping links. If a required entity cannot be found, retain the journal and restore a matching world backup before attempting manual repair.

Before writing a new connection intent, Tumble parks the affected components and synchronously saves their entities. It also updates their shared checkpoint when they park and at normal server-save boundaries. After a crash, recovery restores that coherent checkpoint, so movement since the last checkpoint can be lost. A failed checkpoint can leave a component parked until storage is repaired. Connection creation and checkpoint writes are synchronous and can briefly pause the server.

The connection journal uses an atomic replacement after forcing the new file contents. It targets abrupt server-process termination on a local filesystem; Windows directory-entry durability after an operating-system crash or power loss is not guaranteed. The production crash fixture exercises a forced halt immediately after an intent write, including an endpoint spawned after the preceding world save; it does not cover every filesystem failure or crash point.


A prop cannot be linked while it is riding another entity or carrying passengers. While a connection is pending or active, Tumble rejects riding in either direction, including forced riding. Existing version 2 connection journals lack proof of this restriction: those components remain parked, and existing passenger relationships may load so their data is not discarded. Keep the journal and a world backup and determine a safe manual recovery before removing or migrating such a record. Other mods that directly modify entity riding relationships can bypass this guard.
