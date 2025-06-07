package me.voxelsquid.anima

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import me.voxelsquid.anima.configuration.ConfigManager
import me.voxelsquid.anima.humanoid.HumanoidManager
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager.Companion.dialogues
import me.voxelsquid.anima.humanoid.dialogue.menu.InteractionManager.Companion.openedMenuList
import me.voxelsquid.anima.humanoid.dialogue.menu.Menu
import me.voxelsquid.anima.quest.ProgressTracker.Companion.actualQuests
import me.voxelsquid.anima.quest.QuestManager
import me.voxelsquid.anima.runtime.PluginRuntimeController
import me.voxelsquid.anima.settlement.SettlementManager.Companion.settlements
import me.voxelsquid.anima.settlement.SettlementManager.Companion.settlementsWorldKey
import me.voxelsquid.anima.utility.LocationAdapter
import me.voxelsquid.bifrost.Bifrost
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.persistence.PersistentDataType
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
     *  Когда закончишь со всей хуйнёй, не забудь убрать дебаговые саообщения.
     *  Нет википедии. Алсо, я решил не переделывать квестген — я просто доработал его.
     *  Надо добавить команду удаления сеттлментов.
     */

    @EventHandler
    private fun onWorldLoad(event: WorldLoadEvent) {
        if (controller.allowedWorlds.contains(event.world.name)) {
            this.allowedWorlds.add(event.world)
        }

        // First world load. We store a lot of shit in there.
        if (Bukkit.getWorlds()[0] == event.world) {
            actualQuests = Bukkit.getWorlds()[0]!!.persistentDataContainer.get(NamespacedKey(this, "ActualQuests"), PersistentDataType.LONG_ARRAY)?.toMutableList() ?: actualQuests.toLongArray().also {
                Bukkit.getWorlds()[0]!!.persistentDataContainer.set(NamespacedKey(this, "ActualQuests"), PersistentDataType.LONG_ARRAY, it)
            }.toMutableList()
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

    override fun onDisable() {
        dialogues.values.forEach(DialogueManager.DialogueWindow::destroy)
        openedMenuList.forEach(Menu::destroy)
        allowedWorlds.forEach { world ->
            world.persistentDataContainer.set(settlementsWorldKey, PersistentDataType.STRING, gson.toJson(settlements[world]?.map { it.data }))
        }
        Bukkit.getWorlds()[0]!!.persistentDataContainer.set(NamespacedKey(this, "ActualQuests"), PersistentDataType.LONG_ARRAY, actualQuests.toLongArray())
        controller.databaseManager.closeConnection()
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
