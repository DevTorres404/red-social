param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$prefix = 'pfg_' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$ids = [System.Collections.Generic.List[string]]::new()
$envValues = @{}
Get-Content (Join-Path $PSScriptRoot '..\.env') | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $envValues[$matches[1]] = $matches[2].Trim('"', "'") }
}
$credential = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$($envValues.NEO4J_USER):$($envValues.NEO4J_PASSWORD)"))

function Api($method, $path, $token = $null, $payload = $null) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    $args = @{ Uri = "$BaseUrl$path"; Method = $method; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = 20 }
    if ($method -in @('POST', 'PUT', 'PATCH', 'DELETE')) { $args.ContentType = 'application/json' }
    if ($null -ne $payload) {
        $args.Body = ConvertTo-Json -InputObject $payload -Depth 8 -Compress
    }
    $response = Invoke-WebRequest @args
    $data = if ($response.Content) { try { ConvertFrom-Json -InputObject $response.Content } catch { $response.Content } } else { $null }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Data = $data }
}

function Cypher($statement, $parameters) {
    $body = @{ statements = @(@{ statement = $statement; parameters = $parameters }) } | ConvertTo-Json -Depth 10 -Compress
    $result = Invoke-RestMethod -Uri 'http://localhost:7474/db/neo4j/tx/commit' -Method POST -ContentType 'application/json' -Headers @{ Authorization = "Basic $credential" } -Body $body
    if ($result.errors.Count) { throw $result.errors[0].message }
    return $result.results[0].data
}

function Check($condition, $label) {
    if (-not $condition) { throw "FAIL $label" }
    Write-Host "PASS $label"
}

function Register($letter) {
    $response = Api POST '/api/auth/register' $null @{ username = "${prefix}_$letter"; email = "${prefix}_$letter@example.test"; password = 'DemoPass123!' }
    Check ($response.Status -eq 201) "register $letter"
    $ids.Add($response.Data.user.id)
    return $response.Data
}

try {
    $a = Register 'a'; $b = Register 'b'; $c = Register 'c'; $d = Register 'd'; $e = Register 'e'
    $noAuth = Api GET '/api/graph/reachable'
    Check ($noAuth.Status -eq 401) 'graph requires authentication'
    foreach ($edge in @(
        [pscustomobject]@{ from = $a; to = $b },
        [pscustomobject]@{ from = $a; to = $c },
        [pscustomobject]@{ from = $b; to = $c },
        [pscustomobject]@{ from = $b; to = $d },
        [pscustomobject]@{ from = $c; to = $d }
    )) {
        $follow = Api POST "/api/users/$($edge.to.user.id)/follow" $edge.from.token
        Check ($follow.Status -eq 200) "follow edge ($($follow.Status))"
    }
    $q1 = Api GET "/api/graph/common/$($b.user.id)" $a.token
    Check ($q1.Status -eq 200 -and @($q1.Data).Count -eq 1 -and $q1.Data[0].id -eq $c.user.id) 'Q1 shared follows'
    Check ((Api GET "/api/graph/common/$($e.user.id)" $a.token).Data.Count -eq 0) 'Q1 empty intersection'
    $q2 = Api GET '/api/graph/reachable' $a.token
    Check ($q2.Status -eq 200 -and @($q2.Data).Count -eq 3) 'Q2 bounded two-hop set'
    Check (@($q2.Data | Where-Object { $_.id -eq $d.user.id -and $_.distance -eq 2 }).Count -eq 1) 'Q2 reaches two hops'
    Check (@($q2.Data | Where-Object id -eq $e.user.id).Count -eq 0) 'Q2 excludes outsider'
    $q3 = Api GET '/api/graph/recommendations' $a.token
    Check ($q3.Status -eq 200 -and @($q3.Data).Count -eq 1 -and $q3.Data[0].id -eq $d.user.id -and $q3.Data[0].mutualCount -eq 2) 'Q3 explainable recommendation'

    $key = Api GET '/api/push/public-key' $a.token
    Check ($key.Status -eq 200 -and $key.Data.publicKey.Length -gt 50) 'VAPID public key configured'
    Check ((Api POST '/api/push/subscriptions' $a.token @{ endpoint = 'http://127.0.0.1/push'; keys = @{ p256dh = $key.Data.publicKey; auth = 'AAAAAAAAAAAAAAAAAAAAAA' } }).Status -eq 400) 'reject unsafe push endpoint'
    $endpoint = "https://fcm.googleapis.com/fcm/send/$prefix"
    $endpoint2 = "https://fcm.googleapis.com/fcm/send/${prefix}_device2"
    Check ((Api POST '/api/push/subscriptions' $a.token @{ endpoint = $endpoint; keys = @{ p256dh = $key.Data.publicKey; auth = 'AAAAAAAAAAAAAAAAAAAAAA' } }).Status -eq 204) 'subscribe one browser'
    Check ((Api POST '/api/push/subscriptions' $a.token @{ endpoint = $endpoint; keys = @{ p256dh = $key.Data.publicKey; auth = 'AAAAAAAAAAAAAAAAAAAAAA' } }).Status -eq 204) 'repeat subscription is idempotent'
    Check ((Api POST '/api/push/subscriptions' $a.token @{ endpoint = $endpoint2; keys = @{ p256dh = $key.Data.publicKey; auth = 'AAAAAAAAAAAAAAAAAAAAAA' } }).Status -eq 204) 'subscribe second device'
    Check ((Api DELETE '/api/push/subscriptions' $c.token @{ endpoint = $endpoint }).Status -eq 204) 'other user cannot remove device'

    $b1 = Api POST '/api/posts' $b.token @{ content = 'B post one' }
    Start-Sleep -Milliseconds 500
    $queued = Cypher 'MATCH (d:PushDelivery {postId: $postId}) RETURN count(d) AS count' @{ postId = $b1.Data.id }
    Check ($queued[0].row[0] -eq 2) 'one durable push job per follower device'
    $b2 = Api POST '/api/posts' $b.token @{ content = 'B post two' }
    $c1 = Api POST '/api/posts' $c.token @{ content = 'C post one' }
    $e1 = Api POST '/api/posts' $e.token @{ content = 'E outsider post' }
    Check ($b1.Status -eq 201 -and $b2.Status -eq 201 -and $c1.Status -eq 201 -and $e1.Status -eq 201) 'posts persist despite push queue'
    Check ((Api POST "/api/posts/$($b1.Data.id)/like" $a.token).Status -eq 204) 'like one'
    Check ((Api POST "/api/posts/$($b1.Data.id)/like" $c.token).Status -eq 204) 'like two'
    Check ((Api POST "/api/posts/$($c1.Data.id)/like" $a.token).Status -eq 204) 'like three'
    $q4 = Api GET '/api/graph/network-posts' $a.token
    Check ($q4.Status -eq 200 -and @($q4.Data).Count -eq 3 -and @($q4.Data | Where-Object id -eq $e1.Data.id).Count -eq 0) 'Q4 only followed network posts'
    $q5 = Api GET '/api/graph/trending-posts' $a.token
    Check ($q5.Status -eq 200 -and $q5.Data[0].id -eq $b1.Data.id -and $q5.Data[0].likeCount -eq 2) 'Q5 reaction rank'
    Check (@($q5.Data | Select-Object -ExpandProperty id -Unique).Count -eq 3) 'Q5 no duplicate posts'

    Check ((Api DELETE "/api/users/$($b.user.id)/follow" $a.token).Status -eq 200) 'unfollow before delivery'
    Start-Sleep -Seconds 12
    $queuedAfter = Cypher 'MATCH (d:PushDelivery {postId: $postId}) RETURN count(d) AS count' @{ postId = $b1.Data.id }
    Check ($queuedAfter[0].row[0] -eq 0) 'unfollowed recipient job discarded'
    Check ((Api DELETE '/api/push/subscriptions' $a.token @{ endpoint = $endpoint }).Status -eq 204) 'unsubscribe browser'
    Check ((Api DELETE '/api/push/subscriptions' $a.token @{ endpoint = $endpoint2 }).Status -eq 204) 'unsubscribe second device'

}
finally {
    if ($ids.Count) {
        $cleanup = @'
MATCH (u:Usuario) WHERE u.id IN $ids AND u.username STARTS WITH $prefix
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
'@
        Cypher $cleanup @{ ids = @($ids); prefix = $prefix } | Out-Null
        Cypher 'MATCH (d:PushDelivery) WHERE d.recipientId IN $ids DETACH DELETE d' @{ ids = @($ids) } | Out-Null
        Write-Output 'PASS exact-ID test data cleanup'
    }
}
