package com.ddzhilian.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CallProtocolTest {
    @Test fun callControlMatchesWebAndRejectsSpoofedOrStaleIdentity() {
        val call = NativeCall("call", CallSession("session", "room", "peer"), "电脑", true, CallPhase.Outgoing)
        val wire = call.control()
        assertEquals("video", wire.getString("kind")); assertEquals("peer", wire.getString("targetDeviceId"))
        val incoming = JSONObject(wire.toString()).put("fromDeviceId", "peer")
        assertTrue(call.matches(incoming))
        assertFalse(call.matches(JSONObject(incoming.toString()).put("callId", "old")))
        assertFalse(call.matches(JSONObject(incoming.toString()).put("fromDeviceId", "stranger")))
        assertFalse(call.matches(JSONObject(incoming.toString()).put("sessionId", "other")))
    }
    @Test fun notificationCursorDoesNotReplayHistoryOwnMessagesOrUnjoinedRooms() {
        val cursor = MessageNotificationCursor()
        fun snapshot(stamp: String, sender: String = "peer", joined: Boolean = true): JSONObject = JSONObject("""{"self":{"deviceId":"self","historyAuthToken":"token","shortCode":"123"},"rooms":[{"roomId":"room","isPublic":true,"members":${if(joined) """[{"deviceId":"self"}]""" else "[]"},"historyTextLatestAt":"$stamp","historyTextLatestSourceDeviceId":"$sender"}]}""")
        fun consume(p: JSONObject) = cursor.consume(parseSnapshot(p), p)
        assertTrue(consume(snapshot("01")).isEmpty())
        assertEquals("room", consume(snapshot("02")).single().first)
        assertTrue(consume(snapshot("02")).isEmpty())
        assertTrue(consume(snapshot("03", "self")).isEmpty())
        assertTrue(consume(snapshot("04", joined = false)).isEmpty())
        cursor.reset(); assertTrue(consume(snapshot("05")).isEmpty())
    }
    @Test fun recreatedSessionCannotReuseExpiredIdForSameRoomAndPeer() {
        val old = CallSession("expired", "room", "peer")
        val other = CallSession("other", "other-room", "peer")
        val renewed = old.copy(id = "fresh")
        assertEquals(listOf(other, renewed), replaceCallSession(listOf(old, other), renewed))
        assertEquals(listOf(other, renewed), replaceCallSession(listOf(other, renewed), renewed))
    }
    @Test fun snapshotSessionsPreserveRoomAndPeerBoundaries() {
        val sessions = parseCallSessions(JSONObject("""{"sessions":[{"sessionId":"s","roomId":"r","peerId":"p"}]}"""))
        assertEquals(CallSession("s", "r", "p"), sessions.single())
    }
}
