param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
$prefix = "comment_$suffix"
$users = @()
$postId = $null
$postToken = $null
$image = Join-Path $env:TEMP "$prefix.png"
$invalid = Join-Path $env:TEMP "$prefix.txt"
$oversized = Join-Path $env:TEMP "$prefix-big.png"
$imageUrl = $null

function Api($method, $path, $token = $null, $body = $null) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    $args = @{ Uri = "$BaseUrl$path"; Method = $method; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = 20 }
    if ($null -ne $body) { $args.ContentType = 'application/json'; $args.Body = ConvertTo-Json -InputObject $body -Compress }
    $response = Invoke-WebRequest @args
    $data = if ($response.Content) { try { $response.Content | ConvertFrom-Json } catch { $response.Content } } else { $null }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Data = $data }
}

function Check($condition, $label) {
    if (-not $condition) { throw "FAIL $label" }
    Write-Output "PASS $label"
}

function Register($letter) {
    $name = "${prefix}_$letter"
    $result = Api POST '/api/auth/register' $null @{ username = $name; email = "$name@example.test"; password = 'DemoPass123!' }
    Check ($result.Status -eq 201) "register $letter"
    $script:users += [pscustomobject]@{ Id = $result.Data.user.id; Name = $name }
    return $result.Data
}

function Cleanup-Users {
    if (-not $script:users.Count) { return }
    $settings = @{}
    Get-Content (Join-Path $PSScriptRoot '..\.env') | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1]] = $matches[2].Trim('"', "'") }
    }
    $neo4jUser = if ($settings.NEO4J_USER) { $settings.NEO4J_USER } else { 'neo4j' }
    $neo4jPassword = if ($settings.NEO4J_PASSWORD) { $settings.NEO4J_PASSWORD } else { 'changeme' }
    $credential = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${neo4jUser}:$neo4jPassword"))
    $ids = @($script:users | ForEach-Object { $_.Id })
    $statements = @(
        @{ statement = 'MATCH (rt:RefreshToken) WHERE rt.userId IN $ids DETACH DELETE rt'; parameters = @{ ids = $ids } },
        @{ statement = 'MATCH (u:Usuario) WHERE u.id IN $ids AND u.username STARTS WITH $prefix DETACH DELETE u'; parameters = @{ ids = $ids; prefix = $prefix } }
    )
    $body = @{ statements = $statements } | ConvertTo-Json -Depth 8 -Compress
    $result = Invoke-RestMethod -Uri 'http://localhost:7474/db/neo4j/tx/commit' -Method POST -ContentType 'application/json' -Headers @{ Authorization = "Basic $credential" } -Body $body
    if ($result.errors.Count -gt 0) { throw "Test user cleanup failed: $($result.errors[0].message)" }
    Write-Output 'PASS exact test user cleanup'
}

try {
    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new(2, 2)
    $bitmap.SetPixel(0, 0, [System.Drawing.Color]::Blue)
    $bitmap.Save($image, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    [System.IO.File]::Copy($image, $invalid)
    [System.IO.File]::WriteAllBytes($oversized, [byte[]]::new(5 * 1024 * 1024 + 1))

    $a = Register 'a'
    $b = Register 'b'
    $postToken = $a.token
    $created = Api POST '/api/posts' $a.token @{ content = 'Comment feature smoke' }
    Check ($created.Status -eq 201) 'create isolated post'
    $postId = $created.Data.id

    Check ((Api POST "/api/posts/$postId/comments" $b.token @{ text = '' }).Status -eq 400) 'reject empty text-only comment'
    $badImage = @(& curl.exe --silent --show-error --write-out "`n%{http_code}" --request POST --header "Authorization: Bearer $($b.token)" --form 'text=test' --form "image=@$invalid;type=image/png" "$BaseUrl/api/posts/$postId/comments/with-image")
    Check ([int]$badImage[-1] -eq 400) 'reject image with invalid extension'
    $bigImage = @(& curl.exe --silent --show-error --write-out "`n%{http_code}" --request POST --header "Authorization: Bearer $($b.token)" --form 'text=test' --form "image=@$oversized;type=image/png" "$BaseUrl/api/posts/$postId/comments/with-image")
    Check ([int]$bigImage[-1] -in @(400, 413)) 'reject image over 5 MiB'

    $plain = Api POST "/api/posts/$postId/comments" $b.token @{ text = 'Texto de prueba' }
    Check ($plain.Status -eq 201 -and $plain.Data.authorId -eq $b.user.id) 'create text comment with linked author'
    $commentId = $plain.Data.id
    Check ((Api PUT "/api/posts/not-a-post/comments/$commentId/reaction" $a.token @{ emoji = '❤️' }).Status -eq 404) 'reject reaction through wrong post'
    Check ((Api PUT "/api/posts/$postId/comments/$commentId/reaction" $a.token @{ emoji = '🔥' }).Status -eq 400) 'reject unsupported emoji'
    Check ((Api PUT "/api/posts/$postId/comments/$commentId/reaction" $null @{ emoji = '❤️' }).Status -eq 401) 'reject unauthenticated reaction'
    $heart = Api PUT "/api/posts/$postId/comments/$commentId/reaction" $a.token @{ emoji = '❤️' }
    Check ($heart.Status -eq 200 -and $heart.Data.reactions.'❤️' -eq 1 -and $heart.Data.myReaction -eq '❤️') 'store first reaction'
    $again = Api PUT "/api/posts/$postId/comments/$commentId/reaction" $a.token @{ emoji = '❤️' }
    Check ($again.Status -eq 200 -and $again.Data.reactions.'❤️' -eq 1) 'repeat reaction is idempotent'
    $switch = Api PUT "/api/posts/$postId/comments/$commentId/reaction" $a.token @{ emoji = '😂' }
    Check ($switch.Status -eq 200 -and $switch.Data.reactions.'😂' -eq 1 -and -not $switch.Data.reactions.'❤️') 'switch emoji without duplicate'
    $otherViewer = Api GET "/api/posts/$postId/comments" $b.token
    Check ($otherViewer.Status -eq 200 -and -not $otherViewer.Data[0].myReaction -and $otherViewer.Data[0].reactions.'😂' -eq 1) 'reaction owner is viewer-specific'
    $removed = Api DELETE "/api/posts/$postId/comments/$commentId/reaction" $a.token
    Check ($removed.Status -eq 200 -and -not $removed.Data.myReaction -and -not $removed.Data.reactions.'😂') 'remove own reaction'

    $raw = @(& curl.exe --silent --show-error --write-out "`n%{http_code}" --request POST --header "Authorization: Bearer $($b.token)" --form 'text=' --form "image=@$image;type=image/png" "$BaseUrl/api/posts/$postId/comments/with-image")
    $imageComment = [pscustomobject]@{ Status = [int]$raw[-1]; Data = (($raw[0..($raw.Count - 2)] -join "`n") | ConvertFrom-Json) }
    Check ($imageComment.Status -eq 201 -and $imageComment.Data.mediaKey -like 'comments/*.png' -and -not $imageComment.Data.text) 'create image-only comment'
    Check ((Api GET "/api/posts/$postId/comments/$($imageComment.Data.id)/media-url").Status -eq 401) 'deny unsigned comment media request'
    $signed = Api GET "/api/posts/$postId/comments/$($imageComment.Data.id)/media-url" $a.token
    Check ($signed.Status -eq 200 -and $signed.Data.url -match 'X-Amz-Signature') 'issue signed comment image URL'
    $imageUrl = $signed.Data.url
    $read = Invoke-WebRequest -Uri $imageUrl -SkipHttpErrorCheck -TimeoutSec 15
    Check ($read.StatusCode -eq 200 -and $read.Headers['Cache-Control'] -match 'no-store') 'read private image without caching'
    $unsigned = Invoke-WebRequest -Uri "http://localhost:9000/red-social/$($imageComment.Data.mediaKey)" -SkipHttpErrorCheck -TimeoutSec 15
    Check ($unsigned.StatusCode -eq 403) 'deny anonymous comment image access'

    Check ((Api DELETE "/api/posts/$postId" $a.token).Status -eq 204) 'delete post and comments'
    $postId = $null
    Check ((Invoke-WebRequest -Uri $imageUrl -SkipHttpErrorCheck -TimeoutSec 15).StatusCode -eq 404) 'delete comment image object with post'
    Write-Output 'COMMENTS_SMOKE_PASS'
} finally {
    if ($postId -and $postToken) { Api DELETE "/api/posts/$postId" $postToken | Out-Null }
    Cleanup-Users
    Remove-Item -LiteralPath $image, $invalid, $oversized -ErrorAction SilentlyContinue
}
