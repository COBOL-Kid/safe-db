package com.safedb.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class WriteVersionResource : DefaultTask() {
    @get:Input abstract val appVersion: Property<String>

    @get:Input abstract val fileName: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val file = outputDir.get().file(fileName.get()).asFile
        file.parentFile.mkdirs()
        file.writeText(appVersion.get())
    }
}
