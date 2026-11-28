# Reconstruiește „Masa de Poker.exe” și „Jarvis.exe” din codul sursă.
# Rulează din folderul arhivei:  powershell -ExecutionPolicy Bypass -File .\construieste.ps1
# Nu trebuie instalat nimic: folosește compilatorul C# inclus în Windows (.NET Framework 4).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $root 'cod-sursa'
$out = Join-Path $root 'aplicatie'
$build = Join-Path $src 'build'
New-Item -ItemType Directory -Force $build, $out | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)

# 1. Pagina jocului (simulatorul) ca document HTML complet
$page = [IO.File]::ReadAllText((Join-Path $src 'joc\simulator.html'), [Text.Encoding]::UTF8)
$i = $page.IndexOf('<div class="app">')
$doc = "<!DOCTYPE html>`n<html lang=`"ro`">`n<head>`n<meta charset=`"utf-8`">`n<meta name=`"viewport`" content=`"width=device-width, initial-scale=1, viewport-fit=cover`">`n" +
       $page.Substring(0, $i).TrimEnd() + "`n</head>`n<body>`n" + $page.Substring($i).TrimEnd() + "`n</body>`n</html>`n"
[IO.File]::WriteAllText((Join-Path $build 'masa-de-poker.html'), $doc, $utf8)

# 2. Pagina lui Jarvis, cu codul comun (evaluare mâini, equity, push/fold, ICM) inclus
$shared = [IO.File]::ReadAllText((Join-Path $src 'online\shared.js'))
$jarvis = [IO.File]::ReadAllText((Join-Path $src 'jarvis\jarvis.html')).Replace('/*SHARED*/', $shared)
[IO.File]::WriteAllText((Join-Path $build 'jarvis.html'), $jarvis, $utf8)

# 3. Compilarea celor două programe
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$win = Join-Path $src 'windows'
$opts = @('/nologo', '/target:winexe', '/optimize+',
  "/resource:$build\masa-de-poker.html,masa-de-poker.html", "/resource:$build\jarvis.html,jarvis.html",
  '/r:System.Windows.Forms.dll', '/r:System.Drawing.dll')
& $csc @opts "/out:$out\Masa de Poker.exe" "/win32icon:$win\masa-de-poker.ico" "$win\MasaDePoker.cs"
& $csc @opts '/define:JARVIS' "/out:$out\Jarvis.exe" "/win32icon:$win\jarvis.ico" "$win\MasaDePoker.cs"
Write-Host "Gata: programele sunt în $out"
