# Undoes CryptUnprotectData for the current user: reads one base64 line from stdin (the DPAPI-protected
# bytes, "DPAPI" prefix already stripped by the caller) and writes one base64 line of the plaintext key
# to stdout. Used only to recover a Chromium profile's cookie-encryption key; the key itself is never
# logged, only passed back the way it came in.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName System.Security
$line = [Console]::In.ReadLine()
$bytes = [Convert]::FromBase64String($line)
$plain = [System.Security.Cryptography.ProtectedData]::Unprotect($bytes, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
[Console]::Out.WriteLine([Convert]::ToBase64String($plain))
