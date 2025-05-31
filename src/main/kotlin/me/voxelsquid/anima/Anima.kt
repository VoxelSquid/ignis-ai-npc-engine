package me.voxelsquid.anima

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import me.voxelsquid.bifrost.Bifrost
import me.voxelsquid.anima.configuration.ConfigManager
import me.voxelsquid.anima.humanoid.HumanoidManager
import me.voxelsquid.anima.quest.QuestManager
import me.voxelsquid.anima.runtime.PluginRuntimeController
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.plugin.java.JavaPlugin

class Anima : JavaPlugin(), Listener {

    lateinit var controller:      PluginRuntimeController
    lateinit var bifrost:         Bifrost
    lateinit var configManager:   ConfigManager
    lateinit var humanoidManager: HumanoidManager
    lateinit var questManager:    QuestManager

    val allowedWorlds: MutableList<World> = mutableListOf()

    @EventHandler
    private fun onWorldLoad(event: WorldLoadEvent) {
        if (controller.allowedWorlds.contains(event.world.name)) {
            this.allowedWorlds.add(event.world)
        }
    }

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
        configManager   = ConfigManager()
        humanoidManager = HumanoidManager()
        questManager    = QuestManager()

        server.pluginManager.registerEvents(this, this)
    }

    val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    init {
        ignisInstance = this
    }

    companion object {
        lateinit var ignisInstance: Anima

        fun Player.sendFormattedMessage(message: String) {
            this.sendMessage(ignisInstance.controller.messagePrefix + message)
        }

    }

}
