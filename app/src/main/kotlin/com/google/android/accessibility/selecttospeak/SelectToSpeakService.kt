package com.google.android.accessibility.selecttospeak

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService

/**
 * 伪装成 Google 官方 Select-to-Speak 无障碍服务，
 * 让 vivo/OPPO/小米等 ROM 的白名单放行。
 */
class SelectToSpeakService : AgentAccessibilityService()
