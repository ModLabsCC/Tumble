# Assembly conversion recovery

Conversion is refused before any source blocks are removed if the initial
conservative terrain sweep exceeds 2,048 section visits. Overlapping collision
groups may count the same section more than once, so the limit depends on the
structure's extent and shape as well as its block count. Select a smaller
structure if this happens. A linked component stays parked while its required
terrain is unloaded or its coverage exceeds the budget.

Vanilla moving-piston and shulker-box animation progress invalidates the
affected collision sections before it changes their shapes. Adjacent sections
are invalidated for immediate-neighbor block updates. Mods that change collision
shapes without block-state updates or these vanilla progress hooks, or use
long-range shape dependencies, are not covered by this guarantee.

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
## Connected prop placement recovery

When the physics gun turns a connected prop into a block, Tumble saves the level and journals the destination before changing it. A crash before the placement commits restores the prop and its links; after commit, recovery finishes the block placement and removes the prop and its links. Recovery checks the destination and entity before normal ticks, and stops with the journal intact if the state is ambiguous. The destination must initially be air; connected props with block entities cannot use this operation.

If placement recovery stops, back up the entire world, including `tumble-prop-placement-journal` and the connection journal, before investigating the reported UUID and destination. Locate the prop and preserve any unexpected block or entity contents. Do not delete the retained placement journal until the prop, destination, and links have been reconciled. The same process-crash and external-mod limitations above apply; mods that move a pending prop outside its recorded chunk or change chunk internals can defeat the absence check. This journal does not cover conversion of an unconnected prop to a block.

## Connected prop deletion recovery

Deleting a connected prop first checkpoints its level and writes a removal intent. Recovery either retains the original prop and links when the intent was only prepared, or finishes removing the prop and its links once the removal was committed. The same checkpoint precedes TNT impact explosions, including for an unconnected TNT prop. If the server stops between that checkpoint and the explosion, the TNT can be consumed without an explosion; explosion effects themselves are not a transaction.

If deletion recovery stops, back up the entire world including `tumble-prop-removal-journal` and the connection journal. Locate the recorded UUID and its saved chunk, preserve any unexpected entities or passenger data, and resolve the reported mismatch before restarting. Never delete a retained journal until the prop and its links have been reconciled. The same server-process-crash scope and external-mod limitations described above apply to pending removals.
