package me.voxelsquid.anima.humanoid

import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.google.common.reflect.TypeToken
import com.google.gson.JsonSyntaxException
import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.gameplay.settlement.Settlement
import me.voxelsquid.anima.humanoid.HumanoidNamespace.spawnerKey
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager
import me.voxelsquid.anima.humanoid.dialogue.menu.InteractionManager
import me.voxelsquid.anima.quest.base.Quest.QuestData
import me.voxelsquid.anima.settlement.SettlementManager
import me.voxelsquid.anima.settlement.SettlementManager.Companion.settlements
import me.voxelsquid.anima.utility.InventorySerializer
import me.voxelsquid.psyche.HumanoidController
import me.voxelsquid.psyche.HumanoidController.Companion.instance
import me.voxelsquid.psyche.HumanoidController.Configuration
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.getVoicePitch
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.getVoiceSound
import me.voxelsquid.psyche.race.RaceManager
import me.voxelsquid.psyche.race.RaceManager.Companion.race
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SuspiciousStewMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.random.Random

class HumanoidManager: Listener {

    val movementController: me.voxelsquid.psyche.HumanoidController = HumanoidController(plugin, plugin.allowedWorlds, Configuration())
    val dialogueManager    = DialogueManager()
    val interactionManager = InteractionManager()
    val settlementManager  = SettlementManager()
    val tradeHandler       = HumanoidTradeHandler()
    val professionManager  = ProfessionManager()

    private val questIntervalTicks = ConfigurationAccessor(path = "core.generation.quest.period", defaultValue = 200L, comments = mutableListOf("Each iteration only ONE villager in the entire world will be selected to generate a new quest.")).get()
    private val foodIntervalTicks  = ConfigurationAccessor(path = "gameplay.core.food-tick-interval", defaultValue = 4800L, comments = mutableListOf("Each iteration ALL villagers in the entire world will eat.")).get()
    private val workIntervalTicks  = ConfigurationAccessor(path = "gameplay.core.work-tick-interval", defaultValue = 2400L, comments = mutableListOf("Each iteration ALL villagers in the entire world will produce items to trade.")).get()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        this.startTickers()
    }

    private fun startTickers() {
        plugin.server.scheduler.runTaskTimer(plugin, { _ -> plugin.questManager.tick() }, 0, questIntervalTicks)
        plugin.server.scheduler.runTaskTimer(plugin, { _ -> professionManager.produceProfessionItem() }, 0, workIntervalTicks)
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            plugin.allowedWorlds.flatMap { it.entities.filterIsInstance<Villager>() }.shuffled().forEachIndexed { index, villager ->
                if (villager.pose != Pose.SLEEPING) {
                    villager.hunger = (villager.hunger - 2.5).coerceAtLeast(0.0)
                    if (villager.hunger <= 17.5) {
                        plugin.server.scheduler.runTaskLater(plugin, { _ -> villager.eat() }, 5 + (index * 2L).coerceAtMost(40) + Random.nextInt(250))
                    }
                }
            }
        }, 0, foodIntervalTicks)
    }

    fun Villager.eat() = subInventory.filterNotNull().find { it.type.isEdible }?.let { food ->
        val sound = when (food.type) {
            Material.HONEY_BOTTLE -> Sound.ITEM_HONEY_BOTTLE_DRINK
            Material.MUSHROOM_STEW, Material.RABBIT_STEW, Material.SUSPICIOUS_STEW -> Sound.ENTITY_GENERIC_DRINK
            else -> Sound.ENTITY_GENERIC_EAT
        }
        instance.entityProvider.asHumanoid(this as LivingEntity).consume(world, food, sound, 3, location, period = 7) {
            takeItemFromQuillInventory(food, 1)
            if (food.type.toString().contains("STEW")) {
                addItemToQuillInventory(ItemStack(Material.BOWL))
                (food.itemMeta as? SuspiciousStewMeta)?.customEffects?.forEach { addPotionEffect(it) }
            }
            if (food.type == Material.HONEY_BOTTLE) addItemToQuillInventory(ItemStack(Material.GLASS_BOTTLE))
            world.playSound(location, getVoiceSound(), 1F, getVoicePitch())
            world.playSound(location, Sound.ENTITY_PLAYER_BURP, 1F, 1F)
            hunger += 7.5
            if (hunger >= 20.0) addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 200, 1))
        }
    }

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
        val race: RaceManager.Race?,
        val subscribers: MutableList<Player> = mutableListOf()
    )

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    companion object HumanoidEntityExtension {

        val plugin = ignisInstance

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