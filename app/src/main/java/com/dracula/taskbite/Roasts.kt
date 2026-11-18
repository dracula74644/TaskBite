package com.dracula.taskbite

enum class RoastKind { OVERDUE, SNOOZE, ABANDON, IDLE, TINY, CREATED, COMPLETED, STARTED }

object Roasts {
    val lines = mapOf(
        RoastKind.OVERDUE to listOf(
            "Your deadline called. I told it you were buffering.",
            "That task is aging like milk. Fancy a sniff?",
            "We missed the deadline. Bold strategy, captain.",
            "Your overdue task has started charging emotional rent.",
            "Plot twist: ignoring it didn't finish it.",
        ),
        RoastKind.SNOOZE to listOf(
            "Tomorrow again? That guy already has enough problems.",
            "Rescheduled. Your future self just flipped a table.",
            "You move deadlines like you're dodging lasers.",
            "Another snooze? Should I fetch the task some pajamas?",
            "Tomorrow called. It wants a different roommate.",
        ),
        RoastKind.ABANDON to listOf(
            "Opened, admired, fled. A true task connoisseur.",
            "Just browsing? Unfortunately, these tasks aren't decorative.",
            "That was a lovely tour of your responsibilities.",
            "You checked everything except a checkbox. Impressive precision.",
            "Leaving already? We haven't even disappointed the progress bar.",
        ),
        RoastKind.IDLE to listOf(
            "Five minutes. Are we waiting for a dramatic soundtrack?",
            "Staring contest update: the task is winning.",
            "I've seen loading screens with more initiative.",
            "Should I yell action? ACTION! Anything?",
            "Your thumb's on sabbatical. Should we send postcards?",
        ),
        RoastKind.TINY to listOf(
            "You've spent longer avoiding it than it actually takes.",
            "Two minutes of effort. We've negotiated longer than that.",
            "It's one email, captain. We're not invading Mars.",
            "This task is shorter than your last snack expedition.",
            "Tiny task. Giant buildup. Where's the movie trailer?",
        ),
        RoastKind.CREATED to listOf(
            "A new task! Look at you, collecting side quests.",
            "Added. Let's try doing this one, shall we?",
            "Ambition detected. I'll alert the progress bar.",
        ),
        RoastKind.COMPLETED to listOf(
            "You did it! I had a whole roast prepared.",
            "Look at that. Actual progress. Suspicious, but delightful.",
            "One down. Your future self sends a tiny applause.",
            "Task defeated. Loot acquired: slightly less chaos.",
            "Fine. That was impressive. Don't make it weird.",
        ),
        RoastKind.STARTED to listOf(
            "We're moving! Someone tell the documentary crew.",
            "Main character energy. Finally, some plot development.",
            "Go get it. I'll supervise dramatically.",
        ),
    )
    private val previous = mutableMapOf<RoastKind, String>()
    @Synchronized fun pick(kind: RoastKind): String {
        val options = lines.getValue(kind)
        return options.filter { it != previous[kind] }.random().also { previous[kind] = it }
    }
}

object ReminderPolicy {
    const val GLOBAL_COOLDOWN = 30 * 60 * 1000L
    const val TASK_COOLDOWN = 4 * 60 * 60 * 1000L
    fun inQuietHours(hour: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> hour >= start && hour < end
        else -> hour >= start || hour < end
    }
    fun eligible(task: Task, now: Long): Boolean = !task.archived && task.completedAt == null &&
        task.dueAt <= now && task.remindAfter <= now
    fun kind(task: Task): RoastKind = if (task.minutes < 5) RoastKind.TINY else RoastKind.OVERDUE
}
