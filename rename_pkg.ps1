# Rename package `dev.drperky.lce.minecraft.*` -> `dev.endcity.*`.
# Also collapses the `.lce.minecraft.` middle. Idempotent: re-running after a partial failure
# is safe because we only move files that still exist at the old paths.

$ErrorActionPreference = "Stop"
$root = "C:\Users\Dan\Documents\Programming\EndCityLCE\EndCity"

# Mapping of old source paths -> new source paths. Every .java under main and test is listed
# explicitly so this script is auditable, not magic.
$moves = @(
    # --- main ---
    @{ From = "$root\src\main\java\dev\drperky\Start.java";
       To   = "$root\src\main\java\dev\endcity\Start.java" },

    @{ From = "$root\src\main\java\dev\drperky\lce\minecraft\server\MinecraftServer.java";
       To   = "$root\src\main\java\dev\endcity\server\MinecraftServer.java" },

    @{ From = "$root\src\main\java\dev\drperky\lce\minecraft\network\NetworkConstants.java";
       To   = "$root\src\main\java\dev\endcity\network\NetworkConstants.java" },

    @{ From = "$root\src\main\java\dev\drperky\lce\minecraft\network\NetworkManager.java";
       To   = "$root\src\main\java\dev\endcity\network\NetworkManager.java" },

    @{ From = "$root\src\main\java\dev\drperky\lce\minecraft\network\connection\ConnectionState.java";
       To   = "$root\src\main\java\dev\endcity\network\connection\ConnectionState.java" },

    @{ From = "$root\src\main\java\dev\drperky\lce\minecraft\network\connection\PlayerConnection.java";
       To   = "$root\src\main\java\dev\endcity\network\connection\PlayerConnection.java" },

    @{ From = "$root\src\main\java\dev\drperky\lce\minecraft\network\packets\Packet.java";
       To   = "$root\src\main\java\dev\endcity\network\packets\Packet.java" },

    @{ From = "$root\src\main\java\dev\drperky\lce\minecraft\network\threads\BroadcastingThread.java";
       To   = "$root\src\main\java\dev\endcity\network\threads\BroadcastingThread.java" },

    @{ From = "$root\src\main\java\dev\drperky\lce\minecraft\network\threads\ConnectionThread.java";
       To   = "$root\src\main\java\dev\endcity\network\threads\ConnectionThread.java" },

    @{ From = "$root\src\main\java\dev\drperky\lce\minecraft\network\utils\SmallIdPool.java";
       To   = "$root\src\main\java\dev\endcity\network\utils\SmallIdPool.java" },

    @{ From = "$root\src\main\java\dev\drperky\lce\minecraft\network\utils\PacketBuffer.java";
       To   = "$root\src\main\java\dev\endcity\network\utils\PacketBuffer.java" },

    # --- test ---
    @{ From = "$root\src\test\java\dev\drperky\lce\minecraft\network\RejectFrameLayoutTest.java";
       To   = "$root\src\test\java\dev\endcity\network\RejectFrameLayoutTest.java" },

    @{ From = "$root\src\test\java\dev\drperky\lce\minecraft\network\utils\SmallIdPoolTest.java";
       To   = "$root\src\test\java\dev\endcity\network\utils\SmallIdPoolTest.java" }
)

Write-Host ">>> Phase 1: move files and rewrite package/import declarations"

foreach ($m in $moves) {
    if (-not (Test-Path $m.From)) {
        Write-Host "  skip (already moved): $($m.From)"
        continue
    }

    # Ensure the destination directory exists.
    $destDir = Split-Path -Parent $m.To
    if (-not (Test-Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    }

    # Read, rewrite, write to new location.
    $content = Get-Content -Raw -Path $m.From

    # Specific rewrites, longest-prefix first so we don't leave dangling fragments.
    $content = $content -replace 'dev\.drperky\.lce\.minecraft\.', 'dev.endcity.'
    $content = $content -replace 'dev\.drperky\.Start', 'dev.endcity.Start'
    $content = $content -replace '(?m)^package dev\.drperky;$', 'package dev.endcity;'
    # Also catch any stray `dev.drperky` reference (comments, JavaDoc).
    $content = $content -replace 'dev\.drperky', 'dev.endcity'

    Set-Content -Path $m.To -Value $content -NoNewline
    Remove-Item -Path $m.From -Force
    Write-Host "  moved: $($m.From -replace [regex]::Escape($root), '') -> $($m.To -replace [regex]::Escape($root), '')"
}

Write-Host ""
Write-Host ">>> Phase 2: remove now-empty old directory tree"

# Remove from deepest to shallowest. Only delete if genuinely empty.
$oldDirs = @(
    "$root\src\main\java\dev\drperky\lce\minecraft\network\connection",
    "$root\src\main\java\dev\drperky\lce\minecraft\network\packets",
    "$root\src\main\java\dev\drperky\lce\minecraft\network\threads",
    "$root\src\main\java\dev\drperky\lce\minecraft\network\utils",
    "$root\src\main\java\dev\drperky\lce\minecraft\network",
    "$root\src\main\java\dev\drperky\lce\minecraft\server",
    "$root\src\main\java\dev\drperky\lce\minecraft",
    "$root\src\main\java\dev\drperky\lce",
    "$root\src\main\java\dev\drperky",
    "$root\src\test\java\dev\drperky\lce\minecraft\network\utils",
    "$root\src\test\java\dev\drperky\lce\minecraft\network",
    "$root\src\test\java\dev\drperky\lce\minecraft",
    "$root\src\test\java\dev\drperky\lce",
    "$root\src\test\java\dev\drperky"
)

foreach ($d in $oldDirs) {
    if (Test-Path $d) {
        $contents = Get-ChildItem -Path $d -Force -ErrorAction SilentlyContinue
        if (-not $contents) {
            Remove-Item -Path $d -Force
            Write-Host "  removed empty: $($d -replace [regex]::Escape($root), '')"
        } else {
            Write-Host "  NOT EMPTY, left alone: $d"
            Get-ChildItem -Path $d -Force | Select-Object -ExpandProperty FullName | ForEach-Object { Write-Host "      still contains: $_" }
        }
    }
}

Write-Host ""
Write-Host ">>> Phase 3: also wipe stale Gradle build output (bin/ and build/) so we don't re-run stale classes"
foreach ($stale in @("$root\bin", "$root\build")) {
    if (Test-Path $stale) {
        Remove-Item -Recurse -Force -Path $stale
        Write-Host "  cleared: $stale"
    }
}

Write-Host ""
Write-Host "DONE"
