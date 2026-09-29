param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$prefix = 'pfw_' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$createdIds = [System.Collections.Generic.List[string]]::new()
$postId = $null

function Api($method, $path, $token = $null, $body = $null) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    $args = @{ Uri = "$BaseUrl$path"; Method = $method; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = 15 }
    if ($method -eq 'POST') { $args.ContentType = 'application/json' }
    if ($null -ne $body) { $args.Body = ConvertTo-Json -InputObject $body -Compress }
    $response = Invoke-WebRequest @args
    $data = try { $response.Content | ConvertFrom-Json } catch { $response.Content }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Data = $data }
}

function Check($condition, $label) {
    if (-not $condition) { throw "FAIL $label" }
    Write-Output "PASS $label"
}

function Register($letter) {
    $name = "${prefix}_$letter"
    $response = Api POST '/api/auth/register' $null @{ username = $name; email = "$name@example.test"; password = 'DemoPass123!' }
    Check ($response.Status -eq 201) "register $letter" | Out-Host
    $createdIds.Add($response.Data.user.id)
    return $response.Data
}

function Cleanup {
    if ($createdIds.Count -eq 0) { return }
    $settings = @{}
    Get-Content (Join-Path $PSScriptRoot '..\.env') | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1]] = $matches[2].Trim('"', "'") }
    }
    $neo4jUser = if ($settings.NEO4J_USER) { $settings.NEO4J_USER } else { 'neo4j' }
    $neo4jPassword = if ($settings.NEO4J_PASSWORD) { $settings.NEO4J_PASSWORD } else { 'changeme' }
    $credential = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${neo4jUser}:$neo4jPassword"))
    $statements = @()
    if ($postId) {
        $statements += @{ statement = 'MATCH (p:Post {id: $postId}) OPTIONAL MATCH (p)-[:TIENE_COMENTARIO]->(c:Comentario) WITH p, collect(DISTINCT c) AS comments FOREACH (comment IN comments | DETACH DELETE comment) DETACH DELETE p'; parameters = @{ postId = $postId } }
    }
    $statements += @{ statement = 'MATCH (u:Usuario)-[:TIENE]->(n:Notificacion) WHERE u.id IN $ids AND u.username STARTS WITH $prefix DETACH DELETE n'; parameters = @{ ids = @($createdIds); prefix = $prefix } }
    $statements += @{ statement = 'MATCH (u:Usuario) WHERE u.id IN $ids AND u.username STARTS WITH $prefix DETACH DELETE u'; parameters = @{ ids = @($createdIds); prefix = $prefix } }
    $body = @{ statements = $statements } | ConvertTo-Json -Depth 8 -Compress
    $response = Invoke-RestMethod -Uri 'http://localhost:7474/db/neo4j/tx/commit' -Method POST -ContentType 'application/json' -Headers @{ Authorization = "Basic $credential" } -Body $body
    if ($response.errors.Count -gt 0) { throw "Test cleanup failed: $($response.errors[0].message)" }
    Write-Output 'PASS exact test-user and post cleanup'
}

try {
    $a = Register 'a'; $b = Register 'b'; $c = Register 'c'; $d = Register 'd'
    $aId = $a.user.id

    Check ((Api GET '/api/feed').Status -eq 401) 'feed requires JWT'
    Check ((Api POST '/api/feed/ws-ticket').Status -eq 401) 'feed ticket requires JWT'
    Check ((Api POST "/api/users/$aId/follow" $b.token).Status -eq 200) 'B follows A'
    $post = Api POST '/api/posts' $a.token @{ content = 'Fase feed: publicación en vivo' }
    Check ($post.Status -eq 201) 'A creates a post'
    $postId = $post.Data.id

    $ticketB = Api POST '/api/feed/ws-ticket' $b.token
    $ticketD = Api POST '/api/feed/ws-ticket' $d.token
    Check ($ticketB.Status -eq 200 -and $ticketD.Status -eq 200) 'issue feed WebSocket tickets'
    Check ($ticketB.Data.scope -eq 'feed' -and $ticketB.Data.ticket.Length -gt 40) 'ticket carries feed scope and a token'

    & java (Join-Path $PSScriptRoot 'PhaseFeedWebSocketSmoke.java') $postId $c.token $c.user.id $ticketB.Data.ticket $ticketD.Data.ticket
    if ($LASTEXITCODE -ne 0) { throw "Java WebSocket smoke failed with exit code $LASTEXITCODE" }

    $detail = Api GET "/api/posts/$postId" $a.token
    Check ($detail.Status -eq 200 -and $detail.Data.likeCount -eq 1 -and $detail.Data.commentCount -eq 1) 'REST reflects one like and one comment'
    Write-Output 'PHASE_FEED_WS_SMOKE_PASS'
} finally {
    Cleanup
}