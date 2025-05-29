package me.voxelsquid.ignis.runtime

import me.voxelsquid.ignis.Ignis.Companion.ignisInstance
import me.voxelsquid.ignis.command.CommandController
import me.voxelsquid.ignis.configuration.ConfigurationAccessor

class PluginRuntimeController {

    val translation   = ConfigurationAccessor(path = "core.generative-translation", defaultValue = true, comments = mutableListOf("The plugin messages will be automatically translated into the language specified above when plugin enables. But you can disable this and configure everything yourself.")).get()
    val allowedWorlds = ConfigurationAccessor(path = "core.allowed-worlds", defaultValue = listOf("world", "world_nether", "world_the_end"), comments = mutableListOf("Specify the names of the worlds where you want Ignis to work.")).get()
    val naming        = ConfigurationAccessor(path = "core.naming", defaultValue = "English Names").get()
    val setting       = ConfigurationAccessor(path = "core.setting", defaultValue = "Fantasy", comments = mutableListOf("The setting affects the overall atmosphere of the generated content.", "The default is Fantasy, but you can use anything (or describe your own setting in detail).")).get()
    val swearing      = ConfigurationAccessor(path = "core.swearing", defaultValue = true, comments = mutableListOf("Some personality types will swear in their phrases. You may not like this, so I've added the option to disable it.")).get()
    val messagePrefix = ConfigurationAccessor(path = "text-formatting.chat.message-prefix", defaultValue = "#ff5a02🔥 &8| &7", comments = mutableListOf("The prefix of messages sent by the plugin.")).get()

    val personalityGenerationPeriod = ConfigurationAccessor(path = "core.generation.personality.period", defaultValue = 100L).get()

    init {
        this.checkBeforeStartup()
    }

    val databaseManager = DatabaseManager(ignisInstance)

    private fun checkBeforeStartup() {

        val plugin = ignisInstance
        val pluginManager = plugin.server.pluginManager

        // Check for incompatible plugins.
        val incompatiblePlugins = listOf("RealisticVillagers")
        incompatiblePlugins.forEach { name ->
            if (pluginManager.isPluginEnabled(name)) {
                plugin.logger.severe("Ignis detected incompatible plugin: $name.")
                plugin.logger.severe("Ignis will be disabled.")
                pluginManager.disablePlugin(plugin)
                return
            }
        }

    }

    fun isOk() : Boolean = ignisInstance.isEnabled
    fun setupCommands() = CommandController()

}