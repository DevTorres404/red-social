param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$prefix = 'pe_' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$createdIds = [System.Collections.Generic.List[string]]::new()
$conversationId = $null

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
    if ($conversationId) {
        $statements += @{ statement = 'MATCH (m:Mensaje {conversacionId: $conversationId}) DETACH DELETE m'; parameters = @{ conversationId = $conversationId } }
        $statements += @{ statement = 'MATCH (c:Conversacion {id: $conversationId}) DETACH DELETE c'; parameters = @{ conversationId = $conversationId } }
    }
    $statements += @{ statement = 'MATCH (u:Usuario)-[:TIENE]->(n:Notificacion) WHERE u.id IN $ids AND u.username STARTS WITH $prefix DETACH DELETE n'; parameters = @{ ids = @($createdIds); prefix = $prefix } }
    $statements += @{ statement = 'MATCH (u:Usuario) WHERE u.id IN $ids AND u.username STARTS WITH $prefix DETACH DELETE u'; parameters = @{ ids = @($createdIds); prefix = $prefix } }
    $body = @{ statements = $statements } | ConvertTo-Json -Depth 8 -Compress
    $response = Invoke-RestMethod -Uri 'http://localhost:7474/db/neo4j/tx/commit' -Method POST -ContentType 'application/json' -Headers @{ Authorization = "Basic $credential" } -Body $body
    if ($response.errors.Count -gt 0) { throw "Test cleanup failed: $($response.errors[0].message)" }
    Write-Output 'PASS exact test-user and conversation cleanup'
}

try {
    $a = Register 'a'; $b = Register 'b'; $c = Register 'c'
    $aId = $a.user.id; $bId = $b.user.id; $cId = $c.user.id
    Check ((Api GET "/api/messages/$bId").Status -eq 401) 'history requires JWT'
    Check ((Api GET '/api/messages/missing-user' $a.token).Status -eq 404) 'missing recipient returns 404'
    Check ((Api GET "/api/messages/$aId" $a.token).Status -eq 400) 'self conversation rejected'
    Check ((Api GET "/api/messages/${bId}?skip=-1" $a.token).Status -eq 400) 'invalid history page rejected'
    $empty = Api GET "/api/messages/$bId" $a.token
    Check ($empty.Status -eq 200 -and @($empty.Data).Count -eq 0) 'new conversation has empty history'

    $ticketA = Api POST '/api/messages/ws-ticket' $a.token @{ otherUserId = $bId }
    $ticketB = Api POST '/api/messages/ws-ticket' $b.token @{ otherUserId = $aId }
    $ticketA2 = Api POST '/api/messages/ws-ticket' $a.token @{ otherUserId = $bId }
    $ticketC = Api POST '/api/messages/ws-ticket' $c.token @{ otherUserId = $aId }
    Check ($ticketA.Status -eq 200 -and $ticketB.Status -eq 200 -and $ticketA2.Status -eq 200 -and $ticketC.Status -eq 200) 'issue scoped WebSocket tickets'
    $conversationId = $ticketA.Data.conversationId
    Check ($conversationId -eq $ticketB.Data.conversationId -and $conversationId -ne $ticketC.Data.conversationId) 'ticket binds directed participants to one conversation'

    & java (Join-Path $PSScriptRoot 'PhaseEWebSocketSmoke.java') $conversationId $ticketA.Data.ticket $ticketB.Data.ticket $ticketA2.Data.ticket $ticketC.Data.ticket
    if ($LASTEXITCODE -ne 0) { throw "Java WebSocket smoke failed with exit code $LASTEXITCODE" }

    $history = Api GET "/api/messages/$bId" $a.token
    Check ($history.Status -eq 200 -and @($history.Data).Count -eq 2) 'reconnect recovers two persisted messages without duplicates'
    $bInbox = Api GET '/api/notifications' $b.token
    Check ($bInbox.Status -eq 200 -and @($bInbox.Data | Where-Object { $_.type -eq 'MESSAGE' -and $_.triggeredByUserId -eq $aId }).Count -eq 1) 'WebSocket retry creates one Orbit toast event for B'
    $aInbox = Api GET '/api/notifications' $a.token
    Check ($aInbox.Status -eq 200 -and @($aInbox.Data | Where-Object { $_.type -eq 'MESSAGE' -and $_.triggeredByUserId -eq $bId }).Count -eq 1) 'offline WebSocket message creates Orbit event for A'
    $first = Api GET "/api/messages/${bId}?skip=0&limit=1" $a.token
    $second = Api GET "/api/messages/${bId}?skip=1&limit=1" $a.token
    Check (@($first.Data)[0].id -ne @($second.Data)[0].id) 'history pagination does not overlap'
    $private = Api GET "/api/messages/$bId" $c.token
    Check (@($private.Data).Count -eq 0) 'third user cannot read A-B history'
    Write-Output 'PHASE_E_SMOKE_PASS'
} finally {
    Cleanup
}
