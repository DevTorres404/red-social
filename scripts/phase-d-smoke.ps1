param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
$username = "pd_$suffix"
$userId = $null
$token = $null
$postId = $null
$key = $null
$image = Join-Path $env:TEMP "$username.png"
$invalid = Join-Path $env:TEMP "$username.txt"
$oversized = Join-Path $env:TEMP "$username-big.png"

function Api($method, $path, $token = $null, $body = $null, $form = $null) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    $args = @{ Uri = "$BaseUrl$path"; Method = $method; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = 30 }
    if ($null -ne $form) { $args.Form = $form }
    elseif ($null -ne $body) { $args.ContentType = 'application/json'; $args.Body = ConvertTo-Json -InputObject $body -Compress }
    $response = Invoke-WebRequest @args
    $data = try { $response.Content | ConvertFrom-Json } catch { $response.Content }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Data = $data }
}

function Check($condition, $label) {
    if (-not $condition) { throw "FAIL $label" }
    Write-Output "PASS $label"
}

function Cleanup-User {
    if (-not $userId) { return }
    $settings = @{}
    Get-Content (Join-Path $PSScriptRoot '..\.env') | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1]] = $matches[2].Trim('"', "'") }
    }
    $neo4jUser = if ($settings.NEO4J_USER) { $settings.NEO4J_USER } else { 'neo4j' }
    $neo4jPassword = if ($settings.NEO4J_PASSWORD) { $settings.NEO4J_PASSWORD } else { 'changeme' }
    $credential = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${neo4jUser}:$neo4jPassword"))
    $statement = 'MATCH (u:Usuario {id: $id}) WHERE u.username = $username DETACH DELETE u'
    $body = @{ statements = @(@{ statement = $statement; parameters = @{ id = $userId; username = $username } }) } | ConvertTo-Json -Depth 8 -Compress
    $result = Invoke-RestMethod -Uri 'http://localhost:7474/db/neo4j/tx/commit' -Method POST -ContentType 'application/json' -Headers @{ Authorization = "Basic $credential" } -Body $body
    if ($result.errors.Count -gt 0) { throw "Test user cleanup failed: $($result.errors[0].message)" }
    Write-Output 'PASS exact test user cleanup'
}

try {
    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new(2, 2)
    $bitmap.SetPixel(0, 0, [System.Drawing.Color]::Red)
    $bitmap.Save($image, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    [System.IO.File]::Copy($image, $invalid)
    [System.IO.File]::WriteAllBytes($oversized, [byte[]]::new(5 * 1024 * 1024 + 1))

    $registration = Api POST '/api/auth/register' $null @{ username = $username; email = "$username@example.test"; password = 'DemoPass123!' }
    Check ($registration.Status -eq 201) 'register isolated media user'
    $userId = $registration.Data.user.id
    $token = $registration.Data.token

    Check ((Api POST '/api/posts' $token @{ content = 'bad'; mediaUrl = 'https://example.test/not-an-upload.png' }).Status -eq 400) 'reject arbitrary media URL'
    Check ((Api POST '/api/posts/with-image' $null $null @{ content = 'test'; image = Get-Item $image }).Status -eq 401) 'reject unauthenticated upload'
    Check ((Api POST '/api/posts/with-image' $token $null @{ content = 'test'; image = Get-Item $invalid }).Status -eq 400) 'reject invalid extension and type'
    Check ((Api POST '/api/posts/with-image' $token $null @{ content = 'test'; image = Get-Item $oversized }).Status -eq 400) 'reject image over 5 MiB'
    Check ((Api POST '/api/posts/with-image' $token $null @{ content = 'test' }).Status -eq 400) 'reject missing image part'

    $textOnly = Api POST '/api/posts' $token @{ content = 'phase D text only' }
    Check ($textOnly.Status -eq 201 -and -not $textOnly.Data.mediaKey) 'create text-only post without multimedia'
    Check ((Api GET "/api/posts/$($textOnly.Data.id)/media-url" $token).Status -eq 404) 'text-only post has no media URL'
    Check ((Api DELETE "/api/posts/$($textOnly.Data.id)" $token).Status -eq 204) 'delete text-only test post'

    # Invoke-WebRequest -Form labels FileInfo as application/octet-stream.
    # Supply the browser-equivalent image/png part explicitly for this positive case.
    $raw = @(& curl.exe --silent --show-error --write-out "`n%{http_code}" --request POST --header "Authorization: Bearer $token" --form 'content=phase D image' --form "image=@$image;type=image/png" "$BaseUrl/api/posts/with-image")
    $created = [pscustomobject]@{ Status = [int]$raw[-1]; Data = (($raw[0..($raw.Count - 2)] -join "`n") | ConvertFrom-Json) }
    if ($created.Status -ne 201) { throw "Upload returned HTTP $($created.Status): $($created.Data | ConvertTo-Json -Compress)" }
    Check ($created.Status -eq 201) 'create post with image'
    $postId = $created.Data.id
    $key = $created.Data.mediaKey
    Check ($key -like 'posts/*.png' -and $created.Data.mediaType -eq 'image/png') 'persist generated key and metadata'
    Check ((Api GET "/api/posts/$postId/media-url").Status -eq 401) 'deny unsigned media URL request'
    $signed = Api GET "/api/posts/$postId/media-url" $token
    Check ($signed.Status -eq 200 -and $signed.Data.url -match 'X-Amz-Signature') 'issue short-lived signed URL'
    $imageResponse = Invoke-WebRequest -Uri $signed.Data.url -SkipHttpErrorCheck -TimeoutSec 15
    Check ($imageResponse.StatusCode -eq 200) 'read private object via signed URL'
    $unsigned = Invoke-WebRequest -Uri "http://localhost:9000/red-social/$key" -SkipHttpErrorCheck -TimeoutSec 15
    Check ($unsigned.StatusCode -eq 403) 'deny anonymous object read'

    Start-Sleep -Seconds 65
    $expired = Invoke-WebRequest -Uri $signed.Data.url -SkipHttpErrorCheck -TimeoutSec 15
    Check ($expired.StatusCode -eq 403) 'deny expired signed URL'

    $fresh = Api GET "/api/posts/$postId/media-url" $token
    Check ($fresh.Status -eq 200) 'issue fresh URL before deletion check'

    Check ((Api DELETE "/api/posts/$postId" $token).Status -eq 204) 'delete image post'
    $postId = $null
    $afterDelete = Invoke-WebRequest -Uri $fresh.Data.url -SkipHttpErrorCheck -TimeoutSec 15
    Check ($afterDelete.StatusCode -eq 404) 'delete object from MinIO'
    Write-Output 'PHASE_D_SMOKE_PASS'
} finally {
    if ($postId -and $token) { Api DELETE "/api/posts/$postId" $token | Out-Null }
    Cleanup-User
    Remove-Item -LiteralPath $image, $invalid, $oversized -ErrorAction SilentlyContinue
}
