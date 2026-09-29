param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
$prefix = "ptoken_$suffix"
$createdIds = [System.Collections.Generic.List[string]]::new()

function Invoke-Api($method, $path, $token = $null, $payload = $null, $cookieHeader = $null) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    if ($cookieHeader) { $headers.Cookie = $cookieHeader }
    $args = @{
        Uri = "$BaseUrl$path"
        Method = $method
        Headers = $headers
        SkipHttpErrorCheck = $true
        TimeoutSec = 15
    }
    if ($method -in @('POST', 'PUT', 'PATCH')) { $args.ContentType = 'application/json' }
    if ($null -ne $payload) {
        $args.Body = ConvertTo-Json -InputObject $payload -Depth 6 -Compress
    }
    $response = Invoke-WebRequest @args
    $data = if ($response.Content -and $response.Content.Trim()) {
        try { ConvertFrom-Json -InputObject $response.Content } catch { $response.Content }
    } else { $null }
    $respCookies = if ($response.Headers['Set-Cookie']) { $response.Headers['Set-Cookie'] } else { $null }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Data = $data; Cookies = $respCookies }
}

function Check($condition, $label) {
    if (-not $condition) { throw "FAIL $label" }
    Write-Host "PASS $label"
}

function ExtractCookie($setCookieHeader, $name) {
    if (-not $setCookieHeader) { return $null }
    foreach ($c in $setCookieHeader) {
        foreach ($part in $c -split ';') {
            $part = $part.Trim()
            if ($part.StartsWith("$name=")) {
                return $part.Substring($name.Length + 1)
            }
        }
    }
    return $null
}

function Register-User($letter) {
    $body = @{ username = "${prefix}_$letter"; email = "${prefix}_$letter@example.test"; password = 'DemoPass123!' }
    $result = Invoke-Api POST '/api/auth/register' $null $body
    Check ($result.Status -eq 201) "register $letter"
    $createdIds.Add($result.Data.user.id)
    return $result.Data
}

try {
    Write-Host "=== TOKEN STRATEGY SMOKE ===" -ForegroundColor Cyan

    # 1. Register user A
    Write-Host "1. Register user A..." -ForegroundColor Yellow
    $a = Register-User 'a'

    # 2. Login A - verify refresh token cookie is set
    Write-Host "2. Login A - verify refresh token cookie..." -ForegroundColor Yellow
    $login = Invoke-Api POST '/api/auth/login' $null @{ identifier = $a.user.username; password = 'DemoPass123!' }
    Check ($login.Status -eq 200) 'login succeeds'
    $accessToken = $login.Data.token
    $rtCookie = ExtractCookie $login.Cookies 'rt'
    Check ($rtCookie -ne $null -and $rtCookie.Length -gt 10) 'refresh token cookie (rt) present on login'
    Write-Host "   Access token: $($accessToken.Substring(0,20))..." -ForegroundColor Gray
    Write-Host "   Refresh cookie: $($rtCookie.Substring(0,20))..." -ForegroundColor Gray

    # 3. Verify access token works (GET /me)
    Write-Host "3. Access token works for /me..." -ForegroundColor Yellow
    $me = Invoke-Api GET '/api/auth/me' $accessToken
    Check ($me.Status -eq 200 -and $me.Data.id -eq $a.user.id) 'access token valid for /me'

    # 4. Verify refresh endpoint rotates token (old cookie invalid, new cookie set)
    Write-Host "4. Refresh rotates token..." -ForegroundColor Yellow
    $cookieHeader1 = "rt=$rtCookie"
    $refresh1 = Invoke-Api POST '/api/auth/refresh' $null $null $cookieHeader1
    Check ($refresh1.Status -eq 200) 'refresh succeeds'
    $newAccessToken1 = $refresh1.Data.token
    $newRtCookie1 = ExtractCookie $refresh1.Cookies 'rt'
    Check ($newAccessToken1 -ne $accessToken) 'new access token differs from old'
    Check ($newRtCookie1 -ne $null -and $newRtCookie1 -ne $rtCookie) 'new refresh cookie differs from old (rotation)'
    Write-Host "   New access token: $($newAccessToken1.Substring(0,20))..." -ForegroundColor Gray
    Write-Host "   New refresh cookie: $($newRtCookie1.Substring(0,20))..." -ForegroundColor Gray

    # 5. Old refresh token should be rejected (rotation)
    Write-Host "5. Old refresh token rejected..." -ForegroundColor Yellow
    $oldCookieHeader = "rt=$rtCookie"
    $refreshOld = Invoke-Api POST '/api/auth/refresh' $null $null $oldCookieHeader
    Check ($refreshOld.Status -eq 401) 'old refresh token rejected after rotation'

    # 6. New refresh token works again
    Write-Host "6. New refresh token works again..." -ForegroundColor Yellow
    $cookieHeader2 = "rt=$newRtCookie1"
    $refresh2 = Invoke-Api POST '/api/auth/refresh' $null $null $cookieHeader2
    Check ($refresh2.Status -eq 200) 'second refresh succeeds'
    $newAccessToken2 = $refresh2.Data.token
    $newRtCookie2 = ExtractCookie $refresh2.Cookies 'rt'
    Check ($newAccessToken2 -ne $newAccessToken1) 'access token rotated again'
    Check ($newRtCookie2 -ne $null -and $newRtCookie2 -ne $newRtCookie1) 'refresh cookie rotated again'

    # 7. Logout revokes refresh token and clears cookie
    Write-Host "7. Logout revokes refresh token..." -ForegroundColor Yellow
    $cookieHeader3 = "rt=$newRtCookie2"
    $logout = Invoke-Api POST '/api/auth/logout' $null $null $cookieHeader3
    Check ($logout.Status -eq 200) 'logout succeeds'
    $logoutCookie = ExtractCookie $logout.Cookies 'rt'
    Check ($logoutCookie -eq '' -or $logoutCookie -eq $null) 'logout clears cookie (empty value)'

    # 8. Refresh after logout should fail
    Write-Host "8. Refresh after logout fails..." -ForegroundColor Yellow
    $refreshAfterLogout = Invoke-Api POST '/api/auth/refresh' $null $null $cookieHeader3
    Check ($refreshAfterLogout.Status -eq 401) 'refresh fails after logout'

    # 9. Access token still works until expiry (stateless JWT)
    Write-Host "9. Access token still valid after logout (stateless)..." -ForegroundColor Yellow
    $meAfterLogout = Invoke-Api GET '/api/auth/me' $newAccessToken2
    Check ($meAfterLogout.Status -eq 200) 'access token still works after logout (until expiry)'

    # 10. Verify refresh token revoked in Neo4j (via Cypher)
    Write-Host "10. Verify refresh token revoked in Neo4j..." -ForegroundColor Yellow
    $cred = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('neo4j:redsocial123'))
    $body = @{ statements = @(@{ statement = 'MATCH (rt:RefreshToken) WHERE rt.revokedAt IS NOT NULL RETURN count(rt) AS revoked'; parameters = @{} }) } | ConvertTo-Json -Depth 10 -Compress
    $result = Invoke-RestMethod -Uri 'http://localhost:7474/db/neo4j/tx/commit' -Method POST -ContentType 'application/json' -Headers @{ Authorization = "Basic $cred" } -Body $body
    $revokedCount = $result.results[0].data[0].row[0]
    Check ($revokedCount -ge 1) "at least 1 refresh token revoked in Neo4j (count=$revokedCount)"

    Write-Host "=== TOKEN STRATEGY SMOKE PASS ===" -ForegroundColor Green

}
finally {
    if ($createdIds.Count) {
        Write-Host "Cleanup test users..." -ForegroundColor Yellow
        $idsJson = ($createdIds | ForEach-Object { "`"$_`"" }) -join ','
        $cleanup = @"
MATCH (u:Usuario) WHERE u.id IN [$idsJson]
OPTIONAL MATCH (u)-[:PUBLICO]->(p:Post)
OPTIONAL MATCH (p)-[:TIENE_COMENTARIO]->(c:Comentario)
WITH collect(DISTINCT u) AS users, collect(DISTINCT p) AS posts, collect(DISTINCT c) AS comments
UNWIND users AS user
OPTIONAL MATCH (user)-[:HAS_SUBSCRIPTION]->(s:PushSubscription)
WITH users, posts, comments, collect(DISTINCT s) AS subscriptions
FOREACH (comment IN comments | DETACH DELETE comment)
FOREACH (post IN posts | DETACH DELETE post)
FOREACH (subscription IN subscriptions | DETACH DELETE subscription)
FOREACH (user IN users | DETACH DELETE user)
"@
        $body = @{ statements = @(@{ statement = $cleanup; parameters = @{} }) } | ConvertTo-Json -Depth 10 -Compress
        $cred = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('neo4j:redsocial123'))
        Invoke-RestMethod -Uri 'http://localhost:7474/db/neo4j/tx/commit' -Method POST -ContentType 'application/json' -Headers @{ Authorization = "Basic $cred" } -Body $body | Out-Null
        Write-Host "PASS exact-ID test data cleanup"
    }
}