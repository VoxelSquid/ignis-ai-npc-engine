package me.voxelsquid.anima

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import me.voxelsquid.anima.configuration.ConfigManager
import me.voxelsquid.anima.humanoid.HumanoidManager
import me.voxelsquid.anima.quest.QuestManager
import me.voxelsquid.anima.runtime.PluginRuntimeController
import me.voxelsquid.anima.utility.LocationAdapter
import me.voxelsquid.bifrost.Bifrost
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.plugin.java.JavaPlugin

class Ignis : JavaPlugin(), Listener {

    lateinit var controller:      PluginRuntimeController
    lateinit var bifrost:         Bifrost
    lateinit var configManager:   ConfigManager
    lateinit var humanoidManager: HumanoidManager
    lateinit var questManager:    QuestManager

    val allowedWorlds: MutableList<World> = mutableListOf()

    /*
     * TODO
     *  Не работает ProfessionManager (жители не крафтят шмотки и не торгуют своим говном).
     *  Не работает подсветка предметов.
     *  Когда закончишь со всей хуйнёй, не забудь убрать дебаговые саообщения.
     *  Нет википедии. Алсо, я решил не переделывать квестген — я просто доработал его.
     *  Жителям нельзя дарить подарки.
     *  Необходимо протестить появление жителей болот.
     *  Жители не хавают еду.
     */

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

        server.pluginManager.registerEvents(this, this)

        controller.setupCommands()
        configManager   = ConfigManager()
        humanoidManager = HumanoidManager()
        questManager    = QuestManager()
    }

    val gson: Gson = GsonBuilder().setPrettyPrinting().registerTypeAdapter(Location::class.java, LocationAdapter()).create()

    fun isFree(player: Player? = null) : Boolean {
        val free = false
        if (free) {
            player?.sendFormattedMessage("This feature is only available in the premium version of the plugin. Please support the development by purchasing the plugin on Polymart.")
        }
        return free
    }

    init {
        ignisInstance = this
    }

    companion object {

        lateinit var ignisInstance: Ignis

        fun Player.sendFormattedMessage(message: String) {
            this.sendMessage(ignisInstance.controller.messagePrefix + message)
        }

        fun String.replaceMap(replacements: Map<String, String>): String {
            var result = this
            for ((key, value) in replacements) {
                result = result.replace("{${key}}", value)
            }
            return result
        }

    }

}
