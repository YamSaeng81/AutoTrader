param(
    [string] $BlockPath = 'docs/evidence/v172d4-20261116/d4/blocks-d4/U뒤관측.sh'
)

$ErrorActionPreference = 'Stop'
$blockFile = (Resolve-Path -LiteralPath $BlockPath).Path
$source = [IO.File]::ReadAllText($blockFile)
$offset = $source.IndexOf('echo "== 기동 뒤 HOLD 질의')
if ($offset -lt 0) {
    throw 'The second HOLD inspection segment was not found.'
}

$model = @'
(
set -euo pipefail
M=unused; TS=unused
stop(){ echo "STOP $*"; exit 1; }
head(){ echo baseline; }
tee(){ cat; }
holdobs(){ echo SECOND_READ_P0; return 1; }
entryobs(){ return 0; }
n17obs(){ return 0; }
n19obs(){ return 0; }
n20obs(){ return 0; }
'@
$model += "`n" + $source.Substring($offset)
$utf8 = New-Object System.Text.UTF8Encoding($false)
$processInfo = New-Object System.Diagnostics.ProcessStartInfo
$processInfo.FileName = 'C:/Program Files/Git/bin/bash.exe'
$processInfo.Arguments = '--noprofile --norc'
$processInfo.UseShellExecute = $false
$processInfo.CreateNoWindow = $true
$processInfo.RedirectStandardInput = $true
$processInfo.RedirectStandardOutput = $true
$processInfo.RedirectStandardError = $true
$processInfo.StandardOutputEncoding = $utf8
$processInfo.StandardErrorEncoding = $utf8

$process = New-Object System.Diagnostics.Process
$process.StartInfo = $processInfo
[void] $process.Start()
$bytes = $utf8.GetBytes($model)
$process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
$process.StandardInput.BaseStream.Flush()
$process.StandardInput.Close()
$output = $process.StandardOutput.ReadToEnd()
$errors = $process.StandardError.ReadToEnd()
$process.WaitForExit()
Write-Output $output
if ($errors) {
    Write-Output $errors
}
Write-Output ('source-sha256=' + (Get-FileHash -LiteralPath $blockFile -Algorithm SHA256).Hash.ToLowerInvariant())
Write-Output ('isolated-second-log-branch-rc=' + $process.ExitCode)
