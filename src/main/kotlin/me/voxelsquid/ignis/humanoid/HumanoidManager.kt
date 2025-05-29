package me.voxelsquid.ignis.humanoid

import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.google.common.reflect.TypeToken
import com.google.gson.JsonSyntaxException
import me.voxelsquid.ignis.Ignis.Companion.ignisInstance
import me.voxelsquid.ignis.gameplay.settlement.Settlement
import me.voxelsquid.ignis.gameplay.settlement.SettlementManager.Companion.settlements
import me.voxelsquid.ignis.humanoid.HumanoidNamespace.spawnerKey
import me.voxelsquid.ignis.humanoid.personality.PersonalityManager
import me.voxelsquid.ignis.humanoid.personality.PersonalityManager.Companion.gender
import me.voxelsquid.ignis.humanoid.race.HumanoidGender
import me.voxelsquid.ignis.humanoid.race.HumanoidRaceManager
import me.voxelsquid.ignis.humanoid.race.HumanoidRaceManager.Companion.race
import me.voxelsquid.ignis.quest.base.Quest
import me.voxelsquid.ignis.quest.base.Quest.QuestData
import me.voxelsquid.ignis.utility.InventorySerializer
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import kotlin.random.Random

class HumanoidManager: Listener {

    lateinit var personalityManager: PersonalityManager

    @EventHandler
    private fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (event.spawnReason == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            event.entity.persistentDataContainer.set(spawnerKey, PersistentDataType.STRING, "true")
        }
    }

    @EventHandler
    private fun onVillagerPickupItem(event: EntityPickupItemEvent) {
        (event.entity as? Villager)?.addItemToQuillInventory(event.item.itemStack)
    }

    data class GenericHumanoidData(val sleepInterruptionMessages: MutableList<String>,
                                   val damageMessages: MutableList<String>,
                                   val joblessMessages: MutableList<String>,
                                   val pauperMessages: MutableList<String>,
                                   val noQuestMessages: MutableList<String>)

    enum class InventoryWealth(val minWealth: Int, val maxWealth: Int) {
        BEGGAR(0, 3999),
        POOR(4000, 7999),
        AVERAGE(8000, 11999),
        WELL_OFF(12000, 14999),
        RICH(15000, 29999),
        ELITE(30000, Int.MAX_VALUE);

        companion object {
            fun getWealthLevel(wealth: Int): InventoryWealth {
                return entries.firstOrNull { wealth in it.minWealth..it.maxWealth }
                    ?: BEGGAR
            }
            fun getProfessionLimit(level: Int) : InventoryWealth {
                return when (level) {
                    0 -> BEGGAR
                    1 -> POOR
                    2 -> AVERAGE
                    3 -> WELL_OFF
                    4 -> RICH
                    5 -> ELITE
                    else -> AVERAGE
                }
            }
        }
    }

    data class HumanoidController(
        val entity: LivingEntity,
        val profile: UserProfile,
        val race: HumanoidRaceManager.Race?,
        val subscribers: MutableList<Player> = mutableListOf()
    )

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    companion object HumanoidEntityExtension {

        val plugin = ignisInstance
        val humanoidRegistry = hashMapOf<LivingEntity, HumanoidController>()
        val HUMANOID_VILLAGERS_ENABLED = plugin.server.pluginManager.isPluginEnabled("Characters")

        fun LivingEntity.getHumanoidController() = humanoidRegistry[this]

        fun Villager.getVoiceSound(): Sound =
            persistentDataContainer.get(HumanoidNamespace.voiceKey, PersistentDataType.STRING)?.let {
                Sound.valueOf(it)
            } ?: race.let {
                val voices = if (gender == HumanoidGender.MALE) it.maleVoices else it.femaleVoices
                voices.random().sound.also { sound -> persistentDataContainer.set(HumanoidNamespace.voiceKey, PersistentDataType.STRING, sound.toString()) }
            }

        fun Villager.getVoicePitch() = persistentDataContainer.get(HumanoidNamespace.pitchKey, PersistentDataType.FLOAT) ?: race.let {
            Random.nextDouble(it.maleVoices.random().min, it.maleVoices.random().max).toFloat().also { pitch ->
                persistentDataContainer.set(HumanoidNamespace.pitchKey, PersistentDataType.FLOAT, pitch)
            }
        }

        fun Villager.skin() = race.let { r ->
            persistentDataContainer.get(HumanoidNamespace.skinKey, PersistentDataType.STRING)?.let { skin ->
                val (value, signature) = skin.split(":"); TextureProperty("textures", value, signature)
            } ?: (if (gender == HumanoidGender.MALE) r.maleSkins else r.femaleSkins).random().also {
                persistentDataContainer.set(HumanoidNamespace.skinKey, PersistentDataType.STRING, "${it.value}:${it.signature}")
            }
        }

        fun Villager.addItemToQuillInventory(vararg items: ItemStack) = subInventory.let { inv ->
            items.forEach { it.amount = it.amount.coerceAtMost(it.maxStackSize); inv.addItem(it) }
            persistentDataContainer.set(HumanoidNamespace.inventoryKey, PersistentDataType.STRING, InventorySerializer.inventoryToJSON(inv).toString())
        }

        fun Villager.takeItemFromQuillInventory(item: ItemStack, amountToTake: Int) = subInventory.let { inventory: Inventory ->
            inventory.filterNotNull().find {
                it.isSimilar(item)
            }?.also {
                it.amount -= amountToTake
                persistentDataContainer.set(HumanoidNamespace.inventoryKey, PersistentDataType.STRING, InventorySerializer.inventoryToJSON(inventory).toString())
            }
        }

        val Villager.professionLevelName get() = when (villagerLevel) { 1 -> "NOVICE"; 2 -> "APPRENTICE"; 3 -> "JOURNEYMAN"; 4 -> "EXPERT"; else -> "MASTER" }

        var Villager.settlement: Settlement?
            get() = persistentDataContainer.get(HumanoidNamespace.settlementKey, PersistentDataType.STRING)?.let { name -> settlements[world]?.find { it.data.settlementName == name } }
            set(value) { value?.let { persistentDataContainer.set(HumanoidNamespace.settlementKey, PersistentDataType.STRING, it.data.settlementName) } }

        val Villager.subInventory: Inventory
            get() = persistentDataContainer.get(HumanoidNamespace.inventoryKey, PersistentDataType.STRING)?.let {
                InventorySerializer.inventoryFromJSON(it)
            } ?: Bukkit.createInventory(null, 54).also { inv ->
                race.spawnItems.forEach { item -> inv.addItem(item.build()) }
                persistentDataContainer.set(HumanoidNamespace.inventoryKey, PersistentDataType.STRING, InventorySerializer.inventoryToJSON(inv).toString())
            }

        var Villager.hunger: Double
            get() = persistentDataContainer.get(HumanoidNamespace.hungerKey, PersistentDataType.DOUBLE) ?: 20.0.also { persistentDataContainer.set(
                HumanoidNamespace.hungerKey, PersistentDataType.DOUBLE, it) }
            set(value) { persistentDataContainer.set(HumanoidNamespace.hungerKey, PersistentDataType.DOUBLE, value) }

        val LivingEntity.quests: MutableList<QuestData>
            get() = persistentDataContainer.get(HumanoidNamespace.questDataKey, PersistentDataType.STRING)?.let {
                try {
                    val type = object : TypeToken<MutableList<QuestData>>() {}.type
                    plugin.gson.fromJson(it, type)
                } catch (exception: JsonSyntaxException) {
                    persistentDataContainer.remove(HumanoidNamespace.questDataKey)
                    mutableListOf()
                }
            } ?: mutableListOf()

        fun LivingEntity.addQuest(quest: QuestData) {
            persistentDataContainer.set(HumanoidNamespace.questDataKey, PersistentDataType.STRING, plugin.gson.toJson(quests.apply { add(quest) }))
        }

        fun LivingEntity.removeQuest(quest: QuestData) {
            persistentDataContainer.set(HumanoidNamespace.questDataKey, PersistentDataType.STRING, plugin.gson.toJson(quests.apply { removeIf { it.questID == quest.questID } }))
        }

        fun LivingEntity.isFromSpawner() : Boolean {
            return this.persistentDataContainer.has(spawnerKey, PersistentDataType.STRING)
        }

    }

}