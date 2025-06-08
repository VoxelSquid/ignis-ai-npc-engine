package me.voxelsquid.anima.runtime

import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.command.CommandController
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager.Companion.DialogueFormat

class PluginRuntimeController {

    val translation   = ConfigurationAccessor(path = "core.generative-translation", defaultValue = true, comments = mutableListOf("The plugin messages will be automatically translated into the language specified above when plugin enables. But you can disable this and configure everything yourself.")).get()
    val allowedWorlds = ConfigurationAccessor(path = "core.allowed-worlds", defaultValue = listOf("world", "world_nether", "world_the_end"), comments = mutableListOf("Specify the names of the worlds where you want Ignis to work.")).get()
    val messagePrefix = ConfigurationAccessor(path = "text-formatting.chat.message-prefix", defaultValue = "#ff5a02🔥 &8| &7", comments = mutableListOf("The prefix of messages sent by the plugin.")).get()
    val format        = ConfigurationAccessor(path = "core.default-dialogue-format", defaultValue = DialogueFormat.IMMERSIVE, comments = mutableListOf("The standard format of villagers' dialogs for every player on the server.", "IMMERSIVE: the message from the villager will be received in a cOoL dIaLoGuE wInDoW.", "CHAT: the message from the villager will be received via chat, classic.", "BOTH: well... there's no need to explain, I hope?..")).get()

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