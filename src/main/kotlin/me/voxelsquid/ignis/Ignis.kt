package me.voxelsquid.ignis

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import me.voxelsquid.bifrost.Bifrost
import me.voxelsquid.ignis.configuration.ConfigManager
import me.voxelsquid.ignis.humanoid.HumanoidManager
import me.voxelsquid.ignis.humanoid.personality.PersonalityManager
import me.voxelsquid.ignis.runtime.PluginRuntimeController
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin

class Ignis : JavaPlugin() {

    lateinit var controller:      PluginRuntimeController
    lateinit var bifrost:         Bifrost
    lateinit var configManager:   ConfigManager
    lateinit var humanoidManager: HumanoidManager

    var allowedWorlds: List<World> = mutableListOf()
    override fun onEnable() {

        bifrost = Bifrost.pluginInstance
        if (!bifrost.isEnabled) {
            logger.severe("Bifrost is not enabled or misconfigured. Disabling plugin.")
            server.pluginManager.disablePlugin(this)
            return
        }

        this.controller = PluginRuntimeController()
        if (!controller.isOk())
            return

        controller.setupCommands()
        configManager = ConfigManager()
        allowedWorlds = controller.allowedWorlds.map { server.getWorld(it) ?: throw NullPointerException("Non-existent world specified in the config.yml: $it.") }
        personalityManager = PersonalityManager()
        humanoidManager    = HumanoidManager()
    }

    val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    init {
        ignisInstance = this
    }

    companion object {
        lateinit var ignisInstance: Ignis
    }

}
