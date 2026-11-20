package com.dracula.taskbite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.appwidget.AppWidgetHostView
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.compose
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalGlanceApi::class)
class WidgetRenderTest {
    @Test fun compactMediumAndLargeLayoutsRenderAsRemoteViews() = runBlocking {
        val app = (RuntimeEnvironment.getApplication() as TaskBiteApp)
        app.preferences.onboarded = true
        app.repository.save(Task(title = "Reply to that email", dueAt = System.currentTimeMillis() - 60_000, minutes = 3))
        app.repository.save(Task(title = "Take a little walk", dueAt = System.currentTimeMillis() + 60_000))
        for (size in listOf(DpSize(180.dp, 180.dp), DpSize(280.dp, 240.dp), DpSize(280.dp, 360.dp))) {
            val views = withTimeout(20_000) { TaskBiteWidget().compose(app, size = size, state = emptyPreferences()) }
            val density = app.resources.displayMetrics.density
            val width = (size.width.value * density).toInt()
            val height = (size.height.value * density).toInt()
            val parent = AppWidgetHostView(app)
            val view = views.apply(app, parent)
            parent.addView(view, ViewGroup.LayoutParams(width, height))
            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, width, height)
            fun allText(node: View): List<String> = when (node) {
                is TextView -> listOf(node.text.toString())
                is ViewGroup -> (0 until node.childCount).flatMap { allText(node.getChildAt(it)) }
                else -> emptyList()
            }
            fun descendants(node: View): List<View> = listOf(node) +
                if (node is ViewGroup) (0 until node.childCount).flatMap { descendants(node.getChildAt(it)) } else emptyList()
            val list = descendants(view).filterIsInstance<android.widget.ListView>().single()
            assertTrue("Widget list has no height at $size", list.height > 0)
            val adapter = requireNotNull(list.adapter) { "Widget task adapter missing" }
            assertEquals(2, adapter.count)
            assertTrue(allText(adapter.getView(0, null, list)).contains("Reply to that email"))
            assertTrue(allText(adapter.getView(1, null, list)).contains("Take a little walk"))
            val text = allText(view)
            assertTrue("Missing counts at $size: $text", text.any { it.contains("2 pending") })
            assertTrue("Missing voice shortcut at $size: $text", text.contains("Voice"))
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            parent.draw(Canvas(bitmap))
            File("build/reports/widget-previews").mkdirs()
            File("build/reports/widget-previews/widget-${size.width.value.toInt()}x${size.height.value.toInt()}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
