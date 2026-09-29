param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
$prefix = "pabc_$suffix"
$createdIds = [System.Collections.Generic.List[string]]::new()

function Invoke-Api($method, $path, $token = $null, $payload = $null) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
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
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Data = $data }
}

function Assert-Status($response, $expected, $label) {
    if ($response.Status -ne $expected) {
        throw "$label expected HTTP $expected, got $($response.Status): $($response.Data | ConvertTo-Json -Compress)"
    }
    Write-Output "PASS $label"
}

function Assert-True($condition, $label) {
    if (-not $condition) { throw "FAIL $label" }
    Write-Output "PASS $label"
}

function Register-TestUser($letter) {
    $body = @{ username = "${prefix}_$letter"; email = "${prefix}_$letter@example.test"; password = 'DemoPass123!' }
    $result = Invoke-Api POST '/api/auth/register' $null $body
    Assert-Status $result 201 "register $letter"
    $createdIds.Add($result.Data.user.id)
    return $result.Data
}

function Remove-TestUsers {
    if ($createdIds.Count -eq 0) { return }
    $settings = @{}
    Get-Content (Join-Path $PSScriptRoot '..\.env') | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1]] = $matches[2].Trim('"', "'") }
    }
    $neo4jUser = if ($settings.NEO4J_USER) { $settings.NEO4J_USER } else { 'neo4j' }
    $neo4jPassword = if ($settings.NEO4J_PASSWORD) { $settings.NEO4J_PASSWORD } else { 'changeme' }
    $credential = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${neo4jUser}:$neo4jPassword"))
    $statement = @'
MATCH (u:Usuario)
WHERE u.id IN $ids AND u.username STARTS WITH $prefix
OPTIONAL MATCH (u)-[:PUBLICO]->(p:Post)
OPTIONAL MATCH (p)-[:TIENE_COMENTARIO]->(c:Comentario)
OPTIONAL MATCH (u)-[:TIENE]->(n:Notificacion)
OPTIONAL MATCH (u)-[:ENVIO]->(m:Mensaje)
OPTIONAL MATCH (u)-[:PARTICIPA]->(conversation:Conversacion)
WITH collect(DISTINCT u) AS users, collect(DISTINCT p) AS posts,
     collect(DISTINCT c) AS comments, collect(DISTINCT n) AS notifications,
     collect(DISTINCT m) AS messages, collect(DISTINCT conversation) AS conversations
FOREACH (notification IN notifications | DETACH DELETE notification)
FOREACH (comment IN comments | DETACH DELETE comment)
FOREACH (message IN messages | DETACH DELETE message)
FOREACH (conversation IN conversations | DETACH DELETE conversation)
FOREACH (post IN posts | DETACH DELETE post)
FOREACH (user IN users | DETACH DELETE user)
'@
    $body = @{ statements = @(@{ statement = $statement; parameters = @{ ids = @($createdIds); prefix = $prefix } }) } | ConvertTo-Json -Depth 8 -Compress
    $response = Invoke-RestMethod -Uri 'http://localhost:7474/db/neo4j/tx/commit' -Method POST -ContentType 'application/json' -Headers @{ Authorization = "Basic $credential" } -Body $body
    if ($response.errors.Count -gt 0) { throw "Test-data cleanup failed: $($response.errors[0].message)" }
    Write-Output 'PASS cleanup of test users and their posts (exact IDs only)'
}

try {
    $a = Register-TestUser 'a'
    $b = Register-TestUser 'b'
    $c = Register-TestUser 'c'
    $aId = $a.user.id; $bId = $b.user.id; $cId = $c.user.id
    $aToken = $a.token; $bToken = $b.token; $cToken = $c.token

    Assert-Status (Invoke-Api POST '/api/auth/register' $null @{ username = "${prefix}_other"; email = "${prefix}_a@example.test"; password = 'DemoPass123!' }) 409 'duplicate email rejected'
    Assert-Status (Invoke-Api POST '/api/auth/login' $null @{ email = "${prefix}_a@example.test"; password = 'wrong-password' }) 401 'invalid login rejected'
    $login = Invoke-Api POST '/api/auth/login' $null @{ email = "${prefix}_a@example.test"; password = 'DemoPass123!' }
    Assert-Status $login 200 'login succeeds'
    Assert-True ($login.Data.user.id -eq $aId) 'login identifies correct user'
    $usernameLogin = Invoke-Api POST '/api/auth/login' $null @{ identifier = "${prefix}_a"; password = 'DemoPass123!' }
    Assert-Status $usernameLogin 200 'login accepts username'
    Assert-True ($usernameLogin.Data.user.id -eq $aId) 'username login identifies the same account'
    Assert-Status (Invoke-Api POST '/api/auth/login' $null @{ identifier = "${prefix}_a"; password = 'wrong-password' }) 401 'username login rejects wrong password'
    Assert-Status (Invoke-Api GET '/api/auth/me') 401 'private endpoint without token'
    $me = Invoke-Api GET '/api/auth/me' $aToken
    Assert-Status $me 200 'current user'
    Assert-True ($me.Data.id -eq $aId) 'current user ID matches JWT subject'
    Assert-Status (Invoke-Api PUT "/api/users/$aId" $bToken @{ bio = 'wrong owner' }) 403 'profile ownership'
    $profile = Invoke-Api PUT "/api/users/$aId" $aToken @{ bio = 'phase A-C smoke' }
    Assert-Status $profile 200 'edit own profile'
    Assert-True ($profile.Data.bio -eq 'phase A-C smoke') 'profile change persisted'

    $before = Invoke-Api GET "/api/users/$aId/suggestions" $aToken
    Assert-Status $before 200 'suggestions for new user'
    Assert-True (@($before.Data).Count -eq 0) 'no arbitrary recommendations'
    $discover = Invoke-Api GET '/api/users/discover' $aToken
    Assert-Status $discover 200 'starter discovery loads'
    Assert-True (@($discover.Data | Where-Object id -eq $bId).Count -eq 1) 'new user can discover B'
    Assert-True (@($discover.Data | Where-Object id -eq $aId).Count -eq 0) 'starter discovery excludes self'
    $followStatus = Invoke-Api GET "/api/users/$bId/follow-status" $aToken
    Assert-Status $followStatus 200 'follow status loads'
    Assert-True (-not $followStatus.Data.following) 'new user is not following B'
    Assert-Status (Invoke-Api GET "/api/users/$bId/suggestions" $aToken) 403 'suggestions ownership'
    Assert-Status (Invoke-Api POST "/api/users/$aId/follow" $aToken) 400 'self follow rejected'
    Assert-Status (Invoke-Api POST '/api/users/missing-user/follow' $aToken) 404 'missing follow target'

    $post = Invoke-Api POST '/api/posts' $bToken @{ content = 'phase A-C feed check' }
    Assert-Status $post 201 'create post'
    $postId = $post.Data.id
    $aFeed = Invoke-Api GET '/api/feed' $aToken
    Assert-Status $aFeed 200 'feed before following'
    Assert-True (@($aFeed.Data | Where-Object id -eq $postId).Count -eq 0) 'unfollowed post absent from feed'
    $bFeed = Invoke-Api GET '/api/feed' $bToken
    Assert-True (@($bFeed.Data | Where-Object id -eq $postId).Count -eq 1) 'own post visible after reload'

    Assert-Status (Invoke-Api POST "/api/users/$bId/follow" $aToken) 200 'follow B'
    $followStatus = Invoke-Api GET "/api/users/$bId/follow-status" $aToken
    Assert-True ($followStatus.Data.following) 'follow status reflects follow'
    $discover = Invoke-Api GET '/api/users/discover' $aToken
    Assert-True (@($discover.Data | Where-Object id -eq $bId).Count -eq 0) 'starter discovery excludes followed user'
    Assert-Status (Invoke-Api POST "/api/users/$bId/follow" $aToken) 200 'duplicate follow idempotent'
    $bNotifications = Invoke-Api GET '/api/notifications' $bToken
    Assert-Status $bNotifications 200 'Orbit inbox loads without Push permission'
    Assert-True (@($bNotifications.Data | Where-Object { $_.type -eq 'FOLLOW' -and $_.triggeredByUserId -eq $aId }).Count -eq 1) 'one in-app follow notification after duplicate request'
    $bUnread = Invoke-Api GET '/api/notifications/unread' $bToken
    Assert-True ($bUnread.Data.unread -eq 1) 'in-app unread count'
    Assert-Status (Invoke-Api POST '/api/notifications/read-all' $bToken) 204 'mark Orbit inbox read'
    $bUnread = Invoke-Api GET '/api/notifications/unread' $bToken
    Assert-True ($bUnread.Data.unread -eq 0) 'read state persists'
    $followers = Invoke-Api GET "/api/users/$bId/followers" $bToken
    Assert-True (@($followers.Data | Where-Object id -eq $aId).Count -eq 1) 'one follower edge'
    $bFollowing = Invoke-Api GET "/api/users/$bId/following" $bToken
    Assert-True (@($bFollowing.Data | Where-Object id -eq $aId).Count -eq 0) 'follow does not create reverse edge'
    Assert-Status (Invoke-Api POST "/api/users/$aId/follow" $bToken) 200 'reverse follow is independent'
    Assert-Status (Invoke-Api DELETE "/api/users/$aId/follow" $bToken) 200 'reverse unfollow is independent'
    $followers = Invoke-Api GET "/api/users/$bId/followers" $bToken
    Assert-True (@($followers.Data | Where-Object id -eq $aId).Count -eq 1) 'original follow survives reverse unfollow'
    Assert-Status (Invoke-Api POST "/api/users/$cId/follow" $bToken) 200 'B follows C'
    $recommended = Invoke-Api GET "/api/users/$aId/suggestions" $aToken
    Assert-True (@($recommended.Data | Where-Object id -eq $cId).Count -eq 1) 'two-hop recommendation'
    $aFeed = Invoke-Api GET '/api/feed' $aToken
    Assert-True (@($aFeed.Data | Where-Object id -eq $postId).Count -eq 1) 'followed post visible in feed'
    $cFeed = Invoke-Api GET '/api/feed' $cToken
    Assert-True (@($cFeed.Data | Where-Object id -eq $postId).Count -eq 0) 'unrelated post absent from feed'
    $newBPost = Invoke-Api POST '/api/posts' $bToken @{ content = 'new post for Orbit inbox' }
    Assert-Status $newBPost 201 'create followed-user post'
    $aNotifications = Invoke-Api GET '/api/notifications' $aToken
    Assert-True (@($aNotifications.Data | Where-Object { $_.type -eq 'POST' -and $_.postId -eq $newBPost.Data.id }).Count -eq 1) 'follower sees new post inside Orbit'
    $cNotifications = Invoke-Api GET '/api/notifications' $cToken
    Assert-True (@($cNotifications.Data | Where-Object { $_.postId -eq $newBPost.Data.id }).Count -eq 0) 'unrelated user has no post notification'
    Assert-Status (Invoke-Api GET '/api/feed?skip=-1&limit=20' $aToken) 400 'negative page rejected'
    Assert-Status (Invoke-Api GET '/api/feed?skip=0&limit=101' $aToken) 400 'oversized page rejected'
    $ownPost = Invoke-Api POST '/api/posts' $aToken @{ content = 'phase A-C own post' }
    Assert-Status $ownPost 201 'create own post'
    $firstPage = Invoke-Api GET '/api/feed?skip=0&limit=1' $aToken
    $secondPage = Invoke-Api GET '/api/feed?skip=1&limit=1' $aToken
    Assert-True (@($firstPage.Data).Count -eq 1 -and @($secondPage.Data).Count -eq 1) 'feed pages have bounded results'
    Assert-True (@($firstPage.Data)[0].id -ne @($secondPage.Data)[0].id) 'feed page IDs do not overlap'

    Assert-Status (Invoke-Api POST "/api/posts/$postId/like" $aToken) 204 'like post'
    Assert-Status (Invoke-Api POST "/api/posts/$postId/like" $aToken) 204 'duplicate like idempotent'
    $liked = Invoke-Api GET "/api/posts/$postId" $aToken
    Assert-True ($liked.Data.likeCount -eq 1 -and $liked.Data.likedByCurrentUser) 'like count consistent'
    $bNotifications = Invoke-Api GET '/api/notifications' $bToken
    Assert-True (@($bNotifications.Data | Where-Object { $_.type -eq 'LIKE' -and $_.postId -eq $postId }).Count -eq 1) 'duplicate like does not duplicate Orbit notification'
    Assert-Status (Invoke-Api DELETE "/api/posts/$postId/like" $aToken) 204 'unlike post'
    $unliked = Invoke-Api GET "/api/posts/$postId" $aToken
    Assert-True ($unliked.Data.likeCount -eq 0) 'unlike count consistent'
    Assert-Status (Invoke-Api DELETE '/api/posts/missing-post/like' $aToken) 404 'missing post unlike'
    Assert-Status (Invoke-Api GET '/api/posts/missing-post/comments' $aToken) 404 'missing post comments'
    $comment = Invoke-Api POST "/api/posts/$postId/comments" $aToken @{ text = 'phase A-C comment' }
    Assert-Status $comment 201 'create comment'
    $bNotifications = Invoke-Api GET '/api/notifications' $bToken
    Assert-True (@($bNotifications.Data | Where-Object { $_.type -eq 'COMMENT' -and $_.postId -eq $postId }).Count -eq 1) 'comment reaches Orbit inbox'
    $directMessage = Invoke-Api POST '/api/messages' $aToken @{ recipientId = $bId; text = 'phase A-C inbox message' }
    Assert-Status $directMessage 201 'send direct message for Orbit inbox'
    $bNotifications = Invoke-Api GET '/api/notifications' $bToken
    Assert-True (@($bNotifications.Data | Where-Object { $_.type -eq 'MESSAGE' -and $_.triggeredByUserId -eq $aId }).Count -eq 1) 'message reaches Orbit inbox'
    $withComment = Invoke-Api GET "/api/posts/$postId" $aToken
    Assert-True ($withComment.Data.commentCount -eq 1) 'comment count consistent'
    Assert-Status (Invoke-Api DELETE "/api/posts/$postId" $aToken) 404 'non-owner cannot delete post'
    $profilePosts = Invoke-Api GET "/api/users/$bId/posts" $aToken
    Assert-True (@($profilePosts.Data | Where-Object id -eq $postId).Count -eq 1) 'profile lists publications'
    Assert-Status (Invoke-Api DELETE "/api/posts/$postId" $bToken) 204 'owner deletes post'
    Assert-Status (Invoke-Api DELETE "/api/posts/$($newBPost.Data.id)" $bToken) 204 'followed-user post cleanup'
    Assert-Status (Invoke-Api GET "/api/posts/$postId" $aToken) 404 'deleted post unavailable'
    Assert-Status (Invoke-Api GET "/api/posts/$postId/comments" $aToken) 404 'deleted post comments unavailable'
    Assert-Status (Invoke-Api DELETE "/api/posts/$($ownPost.Data.id)" $aToken) 204 'own post cleanup through API'
    Assert-Status (Invoke-Api DELETE "/api/users/$bId/follow" $aToken) 200 'unfollow B'
    $followStatus = Invoke-Api GET "/api/users/$bId/follow-status" $aToken
    Assert-True (-not $followStatus.Data.following) 'follow status reflects unfollow'
    Assert-Status (Invoke-Api DELETE "/api/users/$bId/follow" $aToken) 200 'duplicate unfollow idempotent'

    Write-Output 'PHASE_A_C_SMOKE_PASS'
} finally {
    Remove-TestUsers
}
