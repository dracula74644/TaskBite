package com.dracula.taskbite

import org.junit.Assert.*
import org.junit.Test

class ReminderPolicyTest {
    @Test fun allRoastsAreUnderTwelveWords() {
        (Roasts.lines.values.flatten() + Roasts.sweetLines.values.flatten()).forEach { line ->
            assertTrue("Too long: $line", line.trim().split(Regex("\\s+")).size < 12)
        }
        assertEquals(RoastKind.entries.toSet(), Roasts.sweetLines.keys)
        listOf(RoastKind.OVERDUE, RoastKind.SNOOZE, RoastKind.ABANDON, RoastKind.IDLE, RoastKind.TINY).forEach {
            assertEquals(5, Roasts.lines.getValue(it).size)
        }
    }
    @Test fun sweetStyleIsOptionalAndRotatesItsOwnLines() {
        RoastKind.entries.forEach { kind ->
            assertTrue(Roasts.pick(kind) in Roasts.lines.getValue(kind))
            var previous = Roasts.pick(kind, TeasingStyle.SWEET)
            repeat(10) {
                val line = Roasts.pick(kind, TeasingStyle.SWEET)
                assertTrue(line in Roasts.sweetLines.getValue(kind))
                assertNotEquals(previous, line)
                previous = line
            }
        }
    }
    @Test fun noImmediateRepeatForOneCategory() {
        var previous = Roasts.pick(RoastKind.OVERDUE)
        repeat(50) { val next = Roasts.pick(RoastKind.OVERDUE); assertNotEquals(previous, next); previous = next }
    }
    @Test fun quietHoursHandleMidnightAndBoundaries() {
        assertTrue(ReminderPolicy.inQuietHours(23, 22, 8))
        assertTrue(ReminderPolicy.inQuietHours(0, 22, 8))
        assertTrue(ReminderPolicy.inQuietHours(22, 22, 8))
        assertFalse(ReminderPolicy.inQuietHours(8, 22, 8))
        assertFalse(ReminderPolicy.inQuietHours(12, 22, 8))
        assertTrue(ReminderPolicy.inQuietHours(13, 12, 15))
        assertFalse(ReminderPolicy.inQuietHours(15, 12, 15))
        assertTrue(ReminderPolicy.inQuietHours(12, 8, 8))
    }
    @Test fun onlyPendingDueTasksOutsideCooldownAreEligible() {
        val t = Task(title = "Reply", dueAt = 1000, remindAfter = 1000)
        assertTrue(ReminderPolicy.eligible(t, 1000))
        assertFalse(ReminderPolicy.eligible(t, 999))
        assertFalse(ReminderPolicy.eligible(t.copy(archived = true), 2000))
        assertFalse(ReminderPolicy.eligible(t.copy(completedAt = 1500), 2000))
        assertFalse(ReminderPolicy.eligible(t.copy(remindAfter = 3000), 2000))
        assertEquals(RoastKind.TINY, ReminderPolicy.kind(t.copy(minutes = 4)))
        assertEquals(RoastKind.OVERDUE, ReminderPolicy.kind(t.copy(minutes = 5)))
    }
    @Test fun backupReadsAreBounded() {
        val bytes = ByteArray(Backup.MAX_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { Backup.readLimited(bytes.inputStream()) }
    }
}
