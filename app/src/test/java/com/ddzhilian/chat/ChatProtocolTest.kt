package com.ddzhilian.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatProtocolTest {
    @Test fun serverUrlsStayOnTheConfiguredOrigin() {
        assertEquals("wss://ws.ddzhilian.com/ws", ServerAddress.parse(DEFAULT_SERVER, false).websocket)
        val address = ServerAddress.parse("https://chat.example.com:8443/", false)
        assertEquals("wss://chat.example.com:8443/ws", address.websocket)
        assertEquals("https://chat.example.com:8443/api/history/text", address.api("api/history/text").toString())
        assertEquals("ws://10.0.2.2:8789/ws", ServerAddress.parse("http://10.0.2.2:8789", true).websocket)
        listOf("http://example.com", "https://user:password@example.com", "https://example.com/ws", "https://example.com?token=secret").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse(raw, false) }
        }
    }
    @Test fun snapshotsDoNotAssumeTextHistoryIsIncluded() {
        val p = JSONObject("""{"self":{"deviceId":"self","historyAuthToken":"ephemeral","shortCode":"123456"},"rooms":[{"roomId":"room","isPublic":true,"publicIndex":1,"members":[{"deviceId":"self","deviceName":"Android","platform":"android","online":true}],"historyTextPreview":"hello"}],"peers":[],"historyTexts":[],"historyFiles":[]}""")
        val s = parseSnapshot(p)
        assertEquals("ephemeral", s.token)
        assertEquals("公共聊天室", s.rooms.single().title("self"))
        assertTrue(s.rooms.single().joined("self"))
        assertTrue(s.files.isEmpty())
    }
    @Test fun historyPaginationPreservesServerCursorAndMessageIdentity() {
        val page = parseHistory(JSONObject("""{"texts":[{"historyId":"m1","roomId":"room","sourceDeviceId":"peer","sourceDeviceName":"Desktop","text":"你好 👋","createdAt":"2026-10-09T10:00:00Z"}],"hasMore":true,"nextCursor":{"historyId":"m1","createdAt":"2026-10-09T10:00:00Z"}}"""))
        assertTrue(page.hasMore)
        assertEquals("m1", page.cursor!!.getString("historyId"))
        assertEquals("你好 👋", page.messages.single().text)
    }
    @Test fun retriesDeduplicateAndRecalledMessagesCannotReappear() {
        val message = Message("stable-id", "room", "self", "Android", "hello", "2026-10-09T10:00:00Z", Delivery.Failed)
        assertEquals("stable-id", textPayload(message).getString("historyId"))
        val merged = mergeMessages(listOf(message), listOf(message.copy(delivery = Delivery.Sent)))
        assertEquals(1, merged.size)
        assertEquals(Delivery.Sent, merged.single().delivery)
        assertTrue(mergeMessages(merged, listOf(message), setOf("stable-id")).isEmpty())
    }
    @Test fun fileMetadataAndPrivateRoomNamesMatchWebProtocol() {
        val file = parseMessage(JSONObject("""{"historyId":"f","roomId":"r","sourceDeviceId":"self","sourceDeviceName":"Android","fileName":"设计稿.png","mimeType":"image/png","size":256,"createdAt":"2026-10-09T10:00:00Z"}"""), true)
        assertEquals("设计稿.png", file.fileName)
        assertEquals(256L, file.size)
        val room = Room("r", false, 0, listOf(Peer("self", "我", "android", true), Peer("peer", "电脑", "web", true)), "", "")
        assertEquals("电脑", room.title("self"))
        assertEquals("局域网聊天室", room.copy(reason = "lan-discovery").title("self"))
    }
}
