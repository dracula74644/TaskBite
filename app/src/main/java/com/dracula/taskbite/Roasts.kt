package com.dracula.taskbite

enum class RoastKind { OVERDUE, SNOOZE, ABANDON, IDLE, TINY, CREATED, COMPLETED, STARTED, SNOOZE_REPEAT, COMPLETED_LATE, PLAN_DAY, NOTIFICATION_CLEARED,
    STILL_WORKING, LOW_PRIORITY, SMALL_LIST, NOTIFICATION_OPENED }

enum class TeasingStyle(val label: String) { CLASSIC("Classic"), SWEET("Sweet teasing") }

object Roasts {
    val lines = mapOf(
        RoastKind.STILL_WORKING to listOf("Still working? Your task is awaiting the season finale.", "Started is lovely. Finished would really steal the show."),
        RoastKind.LOW_PRIORITY to listOf("Everything low priority? Your list is unusually relaxed.", "A very chill list. Let's give something a turn."),
        RoastKind.SMALL_LIST to listOf("Short list. Plenty of room for a little progress.", "A small cast of tasks. Give one its moment."),
        RoastKind.NOTIFICATION_OPENED to listOf("You opened the reminder. The task awaits its turn.", "Attention acquired. A checkbox would make this a sequel."),
        RoastKind.NOTIFICATION_CLEARED to listOf(
            "Nice swipe. Your plans didn't move.",
            "Notification gone. Procrastination remains undefeated.",
            "That swipe counted as cardio, apparently.",
            "You cleared a reminder. Ambitious little detour.",
            "Smooth swipe. Shall we try a checkbox next?",
        ),
        RoastKind.PLAN_DAY to listOf(
            "No tasks? Bold of you to call that a plan.",
            "Your list is empty. Your potential is getting bored.",
            "One tiny task. Give this day a plot.",
            "Blank list, big dreams. Shall we introduce them?",
            "Your first task called. It would like to exist.",
        ),
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
            "Your tasks are waiting. Even their patience needs a break.",
            "A little progress would really confuse this to-do list.",
            "Your list called. It misses those checkmarks.",
            "Plot twist available: finish one small thing.",
            "These tasks won't complete their own character arcs.",
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
        RoastKind.SNOOZE_REPEAT to listOf(
            "Tomorrow again? This task practically lives there now.",
            "Another postponement. Should we start charging this task rent?",
            "Your future self has officially muted this conversation.",
            "This deadline has traveled more than we have.",
            "Rescheduled again. Even the snooze button needs a break.",
        ),
        RoastKind.COMPLETED_LATE to listOf(
            "Late, but done. I'll retire the missing-task poster.",
            "The overdue legend is complete. Historians can finally rest.",
            "You finished it! That deadline can stop haunting us.",
        ),
    )
    val sweetLines = mapOf(
        RoastKind.STILL_WORKING to listOf("Still working, babe? I'm cheering for your little finish.", "Started looks cute. Finished would look even cuter."),
        RoastKind.LOW_PRIORITY to listOf("A gentle list, sweetheart. Pick one little win.", "Low pressure, babe. Your first little step awaits."),
        RoastKind.SMALL_LIST to listOf("A few little tasks, cutie. Let's give one attention.", "Small list, sweetheart. Plenty of room for a victory."),
        RoastKind.NOTIFICATION_OPENED to listOf("You noticed me, babe. Your task would love that too.", "Hey, cutie. One little step while you're here?"),
        RoastKind.NOTIFICATION_CLEARED to listOf(
            "Babe, smooth swipe. One little task next?",
            "Cute dodge, sweetheart. Your plans are still waiting.",
            "You can swipe me away, cutie. I'm still cheering.",
            "Babe, that notification wasn't the task.",
            "Nice swipe, love. A tiny win would look cuter.",
        ),
        RoastKind.PLAN_DAY to listOf("Babe, even your to-do list is playing hard to get.", "One little task, sweetheart. Let me cheer you on.", "Cute face. Empty list. Let's give today a plan."),
        RoastKind.CREATED to listOf("Look at you making plans, cutie. I'm taking notes.", "Added, babe. Now comes the adorable part: doing it.", "Big plans, sweetheart. Let's start with one little win."),
        RoastKind.STARTED to listOf("There you go, babe. That's my favorite plot twist.", "Look at you go, cutie. I'm cheering already.", "Starting looks good on you, sweetheart."),
        RoastKind.COMPLETED to listOf("You did it, babe. Proud of you, obviously.", "Task finished. Cute and capable? Show-off.", "Look at that, sweetheart. A little victory for you."),
        RoastKind.COMPLETED_LATE to listOf("Fashionably late, babe. Still proud you finished.", "Late entrance, lovely finish. Very you, sweetheart.", "Done at last, cutie. I'll retire the dramatic sigh."),
        RoastKind.OVERDUE to listOf("Babe, that deadline wasn't playing hard to get.", "Sweetheart, your task is waiting for its big moment.", "Cute distraction. That deadline still noticed, babe."),
        RoastKind.SNOOZE to listOf("Tomorrow again, babe? Your calendar is blushing.", "Sweetheart, that task has had enough beauty sleep.", "Postponed, cutie. Shall we give tomorrow a fighting chance?"),
        RoastKind.SNOOZE_REPEAT to listOf("Babe, this task has more rain checks than our dates.", "Another tomorrow? Sweetheart, even procrastination needs a day off.", "Cutie, this task is practically renting tomorrow."),
        RoastKind.ABANDON to listOf("Back already, cutie? Your little missions saved your seat.", "Babe, browsing the list doesn't count as a date.", "Welcome back, sweetheart. One little win together?"),
        RoastKind.IDLE to listOf("Babe, your little missions could use a little attention.", "Sweetheart, one small win would look good on you.", "Your tasks miss you, cutie. Give one a moment."),
        RoastKind.TINY to listOf("Babe, this takes less time than picking a selfie.", "Tiny task, sweetheart. You're giving it main-character drama.", "Two minutes, cutie. Then you can resume being mysterious."),
    )
    fun options(kind: RoastKind, style: TeasingStyle = TeasingStyle.CLASSIC): List<String> =
        (if (style == TeasingStyle.SWEET) sweetLines else lines).getValue(kind)
    private val previous = mutableMapOf<Pair<RoastKind, TeasingStyle>, String>()
    @Synchronized fun pick(kind: RoastKind, style: TeasingStyle = TeasingStyle.CLASSIC): String {
        val key = kind to style
        val options = options(kind, style)
        return options.filter { it != previous[key] }.random().also { previous[key] = it }
    }
    fun forAction(kind: RoastKind, task: Task, now: Long = System.currentTimeMillis()): RoastKind = when {
        kind == RoastKind.SNOOZE && task.snoozeCount >= 2 -> RoastKind.SNOOZE_REPEAT
        kind == RoastKind.COMPLETED && task.dueAt < now -> RoastKind.COMPLETED_LATE
        else -> kind
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

object InAppReactionPolicy {
    const val IDLE_DELAY = 5 * 60 * 1000L
    const val NUDGE_COOLDOWN = 5 * 60 * 1000L
    fun canNudge(now: Long, lastReaction: Long): Boolean = now - lastReaction >= NUDGE_COOLDOWN
    fun idleKind(tasks: List<Task>, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): RoastKind? {
        val endToday = java.time.Instant.ofEpochMilli(now).atZone(zone)
            .toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val pending = tasks.filter { !it.archived && it.completedAt == null && it.dueAt < endToday }
        if (pending.isEmpty()) return null
        return if (pending.any { it.minutes < 5 }) RoastKind.TINY else RoastKind.IDLE
    }
}
