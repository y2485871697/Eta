package io.github.mangi.eta.data.repository

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NotificationHistoryRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: NotificationHistoryRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("eta_notification_history.db")
        repository = NotificationHistoryRepository(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase("eta_notification_history.db")
    }

    @Test
    fun `query and package filters only return matching notification`() {
        val now = System.currentTimeMillis()
        repository.record("one", "com.example.food", "订单配送中", "骑手即将送达", null, now)
        repository.record("two", "com.example.chat", "新消息", "今晚见", null, now - 1)

        val result = JSONObject(repository.search("骑手", "com.example.food", 24, 20))

        assertEquals(1, result.getInt("count"))
        assertEquals("com.example.food", result.getJSONArray("items").getJSONObject(0).getString("package_name"))
    }

    @Test
    fun `same notification key replaces prior content and expired records are excluded`() {
        val now = System.currentTimeMillis()
        repository.record("same", "com.example.food", "旧状态", null, null, now - 1)
        repository.record("same", "com.example.food", "已送达", null, null, now)
        repository.record("expired", "com.example.food", "很久以前", null, null, now - 8L * 24 * 60 * 60 * 1_000)

        val serialized = repository.search("", "", 168, 20)
        val result = JSONObject(serialized)

        assertEquals(1, result.getInt("count"))
        assertFalse(serialized.contains("旧状态"))
        assertFalse(serialized.contains("很久以前"))
    }
}
