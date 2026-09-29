param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$name = 'pd_outage_' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$image = Join-Path $env:TEMP "$name.png"
$userId = $null
$token = $null
$rustfsStopped = $false

function Check($condition, $label) {
    if (-not $condition) { throw "FAIL $label" }
    Write-Output "PASS $label"
}

try {
    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new(2, 2)
    try {
        $bitmap.SetPixel(0, 0, [System.Drawing.Color]::Red)
        $bitmap.Save($image, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $bitmap.Dispose() }

    $registrationBody = @{ username = $name; email = "$name@example.test"; password = 'DemoPass123!' } | ConvertTo-Json
    $registration = Invoke-RestMethod -Uri "$BaseUrl/api/auth/register" -Method Post -ContentType 'application/json' -Body $registrationBody
    $userId = $registration.user.id
    $token = $registration.token

    $rustfsStopped = $true
    & docker compose stop rustfs | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop RustFS for outage test' }

    $raw = @(& curl.exe --silent --show-error --max-time 90 --write-out "`n%{http_code}" --request POST `
        --header "Authorization: Bearer $token" --form 'content=outage test' `
        --form "image=@$image;type=image/png" "$BaseUrl/api/posts/with-image")
    $status = [int]$raw[-1]
    Check ($status -eq 503) 'upload returns 503 while RustFS is unavailable'

    $posts = @(Invoke-RestMethod -Uri "$BaseUrl/api/users/$userId/posts" -Headers @{ Authorization = "Bearer $token" } | Where-Object { $_ -and $_.id })
    Check ($posts.Count -eq 0) 'outage creates no Neo4j post'
    Write-Output 'PHASE_D_OUTAGE_SMOKE_PASS'
} finally {
    if ($rustfsStopped) {
        & docker compose start rustfs | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'RustFS restart failed after outage test' }
        $ready = $false
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            try {
                $health = Invoke-WebRequest -Uri 'http://localhost:9000/health' -TimeoutSec 3
                if ($health.StatusCode -eq 200) { $ready = $true; break }
            } catch { Start-Sleep -Seconds 1 }
        }
        Check $ready 'RustFS restored after outage test'
    }
    if ($userId) {
        if ($token) {
            $posts = @(Invoke-RestMethod -Uri "$BaseUrl/api/users/$userId/posts" -Headers @{ Authorization = "Bearer $token" } | Where-Object { $_ -and $_.id })
            foreach ($post in $posts) {
                Invoke-RestMethod -Uri "$BaseUrl/api/posts/$($post.id)" -Method Delete -Headers @{ Authorization = "Bearer $token" } | Out-Null
            }
        }
        $settings = @{}
        Get-Content (Join-Path $PSScriptRoot '..\.env') | ForEach-Object {
            if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1]] = $matches[2].Trim('"', "'") }
        }
        $neo4jUser = if ($settings.NEO4J_USER) { $settings.NEO4J_USER } else { 'neo4j' }
        $neo4jPassword = if ($settings.NEO4J_PASSWORD) { $settings.NEO4J_PASSWORD } else { 'changeme' }
        $credential = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${neo4jUser}:$neo4jPassword"))
        $statement = 'MATCH (u:Usuario {id: $id, username: $name}) DETACH DELETE u'
        $body = @{ statements = @(@{ statement = $statement; parameters = @{ id = $userId; name = $name } }) } | ConvertTo-Json -Depth 8 -Compress
        $result = Invoke-RestMethod -Uri 'http://localhost:7474/db/neo4j/tx/commit' -Method Post -ContentType 'application/json' `
            -Headers @{ Authorization = "Basic $credential" } -Body $body
        if ($result.errors.Count -gt 0) { throw "Test user cleanup failed: $($result.errors[0].message)" }
        Write-Output 'PASS exact outage test-user cleanup'
    }
    Remove-Item -LiteralPath $image -ErrorAction SilentlyContinue
}
