param([string]$Mode, [int]$ProcId)
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition ([IO.File]::ReadAllText("$PSScriptRoot\Cap.cs"))
[Cap]::Run($Mode, $ProcId)
