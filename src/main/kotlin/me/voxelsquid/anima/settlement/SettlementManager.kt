package me.voxelsquid.anima.settlement

import com.google.gson.reflect.TypeToken
import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.gameplay.settlement.Settlement
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.settlement
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.persistence.PersistentDataType

class SettlementManager: Listener {

    private val trackPlayerSettlementEntry = ConfigurationAccessor(path = "settlement.entry.track", defaultValue = true, comments = mutableListOf("Should the plugin send a message to the player that they have entered/left a settlement?")).get()
    private val settlementTitleNameColor   = ConfigurationAccessor(path = "settlement.title.color", defaultValue = "&6", comments = mutableListOf("The color of the settlement name in the title, when entering and exiting a settlement.")).get()

    private val detectionDistance     = 128.0
    private val villagersRequired     = 5
    private val defaultSettlementName = "Default Settlement Name"
    private val reputationManager     = ReputationManager()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    private fun startEnteringTick(world: World) {

        // Check settlement tracking display.
        if (trackPlayerSettlementEntry == false) return

        plugin.server.scheduler.runTaskTimer(plugin, { _ ->

            val enterMessage = plugin.configManager.language.getString("settlement-entering.entering") ?: ""
            val leaveMessage = plugin.configManager.language.getString("settlement-entering.leaving") ?: ""

            world.players.forEach { player ->
                val settlement = settlements[world]!!.find { it.territory.contains(player.location.toVector()) }

                if (settlement != null && player.currentSettlement == null && settlement.data.settlementName != "Default Settlement Name") {
                    val name = settlement.data.settlementName
                    player.sendTitle("$settlementTitleNameColor$name", enterMessage, 20, 40, 20)
                    player.currentSettlement = name
                }

                if (settlement == null && player.currentSettlement != null) {
                    settlements[world]!!.find { it.data.settlementName == player.currentSettlement }
                        ?.let {
                            player.sendTitle("$settlementTitleNameColor${it.data.settlementName}", leaveMessage, 20, 40, 20)
                            player.currentSettlement = null
                        }
                }
            }

        }, 0, 40)
    }

    // Таск, в котором происходит поиск жителей, которые подохдят для создания сетлментов.
    private fun startSettlementDetectionTask(world: World) {

        plugin.server.scheduler.runTaskTimer(plugin, { _ ->

            // Проходимся по всем жителям в мире, которые нигде не "прописаны".
            world.entities.filterIsInstance<Villager>().filter { it.settlement == null }.let { villagers ->

                plugin.server.scheduler.runTask(plugin) { _ ->
                    for (villager in villagers.shuffled()) {

                        // Создаём поселение только с теми жителями, которые нигде не "прописаны"
                        val villagersAround = villager.getNearbyEntities(detectionDistance, detectionDistance, detectionDistance)
                            .filterIsInstance<Villager>()
                            .filter { it.settlement == null }
                            .toMutableList()

                        // Добавляем итерируемого жителя в список
                        villagersAround.add(villager)

                        // Если найденные бездомные находятся на территории поселения (или недалеко от него), то автоматически прописываем их
                        settlements[world]?.forEach { settlement ->
                            villagersAround.forEach {
                                if (settlement.data.center.distance(it.location) <= detectionDistance + detectionDistance / 2) {
                                    it.settlement = settlement
                                }
                            }
                        }

                        // На всякий случай чистим тех, кто мог получить прописку
                        villagersAround.removeIf { it.settlement != null }

                        // Создание поселения моментально, но название будет сгенерировано чуть позже. Возможно, что работа с локациями не может происходить вне тика сервера.
                        if (villagersAround.size >= villagersRequired) {
                            this.generateSettlementName(Settlement(Settlement.SettlementData(world.uid, defaultSettlementName, villager.location, System.currentTimeMillis()), villagers.toMutableSet()))
                            break
                        }

                    }
                }

            }

        }, 0, 200)
    }

    private fun generateSettlementName(settlement: Settlement) {

        data class SettlementData(val settlementName: List<String>)
        val existingNames = if (settlements.isNotEmpty()) "Avoid these names: $settlements." else ""
        val biomeName = settlement.world.getBiome(settlement.data.center).toString().replace("_", " ").lowercase()
        val biome   = biomeName.split(":").getOrNull(1) ?: biomeName
        val setting = plugin.bifrost.setting
        val naming  = plugin.bifrost.namingStyle

        plugin.bifrost.client.sendRequest( // Your task is to generate a creative {namingStyle} name for a settlement which are located in {settlementBiome} biome. Use {language} language. Place the result in the JSON with one key: 'townName'. {extraArguments}
            prompt = "Your task is to generate a creative name, taking into account setting ($setting) and naming style ($naming) for a settlement which are located in $biome biome and put it in JSON with specified keys: " +
                    "‘settlementName’ (array of 5 strings; [come up with different names (from simple to really creative; they must necessarily match the meaning of the biome name, from the meaning to the color), you can play with words]). " +
                    "(You must avoid these settlement names: $existingNames.)",
            responseType = SettlementData::class,
            onSuccess = { settlementData ->
                settlement.data.settlementName = settlementData.settlementName.random()
                settlement.villagers.forEach { villager -> villager.settlement = settlement }
                val settlements = settlements[settlement.world] ?: throw NullPointerException("Missing settlement list in world ${settlement.world.name}!")
                settlements.add(settlement)
                settlement.world.persistentDataContainer.set(settlementsWorldKey, PersistentDataType.STRING, plugin.gson.toJson(settlements.map { it.data }))
            },
            onFailure = { error ->
                println("Error during settlement name generation! (${error.message})")
            }
        )
    }

    private fun loadSettlements(world: World) {
        world.persistentDataContainer.get(settlementsWorldKey, PersistentDataType.STRING)?.let { serializedSettlements ->
            plugin.gson.fromJson(serializedSettlements, object : TypeToken<List<Settlement.SettlementData>>() {})?.let { list ->
                list.forEach { settlementData ->
                    settlements[world]?.add(Settlement(settlementData))
                }
            }
        }
    }

    @EventHandler
    private fun onWorldLoad(event: WorldLoadEvent) {
        if (plugin.controller.allowedWorlds.contains(event.world.name)) {

            settlements[event.world] = mutableListOf()
            this.loadSettlements(event.world)
            this.startEnteringTick(event.world)
            this.startSettlementDetectionTask(event.world)

            // На всякий случай. Лол.
            event.world.entities.filterIsInstance<Villager>().forEach { villager ->
                villager.settlement?.villagers?.add(villager)
            }

        }
    }

    @EventHandler
    private fun onChunkLoad(event: ChunkLoadEvent) {
        event.chunk.entities.filterIsInstance<Villager>().forEach { villager ->
            villager.settlement?.villagers?.add(villager)
        }
    }

    companion object {

        val plugin = ignisInstance
        val settlements: MutableMap<World, MutableList<Settlement>> = mutableMapOf()

        val settlementsWorldKey  = NamespacedKey(plugin, "SettlementList")
        val currentSettlementKey = NamespacedKey(plugin, "CurrentSettlement")

        fun getByName(name: String): Settlement? {
            settlements.values.forEach { settlementList ->
                settlementList.forEach { settlement ->
                    if (settlement.data.settlementName == name) {
                        return settlement
                    }
                }
            }
            return null
        }

        var Player.currentSettlement: String?
            get() = this.persistentDataContainer.get(currentSettlementKey, PersistentDataType.STRING)
            set(value) {
                if (value != null) {
                    this.persistentDataContainer.set(currentSettlementKey, PersistentDataType.STRING, value)
                } else this.persistentDataContainer.remove(currentSettlementKey)
            }
    }

}