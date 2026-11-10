package com.yeopjari.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TbtAdapterTest {
    @Test
    fun `Pleos TurnType 을 어려운 지점으로 바꾼다`() {
        assertEquals(TbtAdapter.Mapped(ManeuverKind.IC, Direction.RIGHT), TbtAdapter.map("TURN_ENTER_IC_R"))
        assertEquals(TbtAdapter.Mapped(ManeuverKind.IC, Direction.LEFT), TbtAdapter.map("TURN_EXIT_IC_L"))
        assertEquals(TbtAdapter.Mapped(ManeuverKind.IC, Direction.NONE), TbtAdapter.map("TURN_EXIT_IC_C"))
        assertEquals(TbtAdapter.Mapped(ManeuverKind.SPLIT, Direction.RIGHT), TbtAdapter.map("TURN_KEEP_R"))
        assertEquals(
            TbtAdapter.Mapped(ManeuverKind.ROUNDABOUT, Direction.NONE, 10),
            TbtAdapter.map("TURN_ENTER_ROUNDABOUT_10"),
        )
        assertEquals(ManeuverKind.OTHER, TbtAdapter.map("TURN_TUNNEL").kind)
        assertEquals(ManeuverKind.TURN, TbtAdapter.map("TURN_LEFT").kind)
    }

    @Test
    fun `합류 방향은 설명에서 찾고 없으면 모른다고 둔다`() {
        assertEquals(Direction.RIGHT, TbtAdapter.map("TURN_DEVIDE_MERGE", "우측 방향 합류").direction)
        assertEquals(Direction.LEFT, TbtAdapter.map("TURN_DEVIDE_MERGE", "왼쪽으로 합류").direction)
        assertEquals(Direction.NONE, TbtAdapter.map("TURN_DEVIDE_MERGE", "합류").direction)
        assertEquals(Direction.NONE, TbtAdapter.map("TURN_DEVIDE_MERGE", null).direction)
    }

    @Test
    fun `같은 지점은 같은 ID, 지나간 뒤 같은 종류가 다시 오면 새 ID`() {
        val a = TbtAdapter()
        val first = a.toUpdate(0, "TURN_DEVIDE_MERGE", 900.0, null, null)
        val same = a.toUpdate(1_000, "TURN_DEVIDE_MERGE", 880.0, null, null)
        assertEquals(first.maneuverId, same.maneuverId)
        // 지나쳐서 거리가 늘어난 경우(재탐색 판단은 엔진 몫)는 같은 ID
        val overshoot = a.toUpdate(2_000, "TURN_DEVIDE_MERGE", 1_400.0, null, null)
        assertEquals(first.maneuverId, overshoot.maneuverId)
        a.toUpdate(3_000, "TURN_DEVIDE_MERGE", 40.0, null, null)
        val nextMerge = a.toUpdate(4_000, "TURN_DEVIDE_MERGE", 2_000.0, null, null)
        assertNotEquals(first.maneuverId, nextMerge.maneuverId)
        val turn = a.toUpdate(5_000, "TURN_RIGHT", 300.0, null, null)
        assertNotEquals(nextMerge.maneuverId, turn.maneuverId)
    }

    @Test
    fun `재탐색 뒤에는 새 ID로 시작한다`() {
        val a = TbtAdapter()
        val before = a.toUpdate(0, "TURN_ENTER_IC_R", 500.0, null, null)
        a.reset()
        val after = a.toUpdate(1_000, "TURN_ENTER_IC_R", 3_000.0, null, null)
        assertNotEquals(before.maneuverId, after.maneuverId)
    }
}
