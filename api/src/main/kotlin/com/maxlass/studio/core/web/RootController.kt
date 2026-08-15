package com.maxlass.studio.core.web

import com.maxlass.studio.settings.service.SettingsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class RootController(
    private val settingsService: SettingsService,
) {

    @GetMapping("/")
    suspend fun status(): String =
        "StoryUnchained Server running | Library path: ${settingsService.getLibraryPath()}"
}
