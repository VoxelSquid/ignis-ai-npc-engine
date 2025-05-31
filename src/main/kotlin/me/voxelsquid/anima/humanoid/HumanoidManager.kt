package me.voxelsquid.anima.humanoid

import com.cryptomorin.xseries.XSound
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.google.common.reflect.TypeToken
import com.google.gson.JsonSyntaxException
import me.voxelsquid.psyche.data.Gender
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.gender
import me.voxelsquid.psyche.race.RaceManager
import me.voxelsquid.psyche.race.RaceManager.Companion.race
import me.voxelsquid.anima.Anima.Companion.ignisInstance
import me.voxelsquid.anima.gameplay.settlement.Settlement
import me.voxelsquid.anima.settlement.SettlementManager.Companion.settlements
import me.voxelsquid.anima.humanoid.HumanoidNamespace.spawnerKey
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager
import me.voxelsquid.anima.quest.base.Quest.QuestData
import me.voxelsquid.anima.utility.InventorySerializer
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import kotlin.random.Random

class HumanoidManager: Listener {

    val dialogueManager:    DialogueManager       = DialogueManager()
    val movementController: me.voxelsquid.psyche.HumanoidController = me.voxelsquid.psyche.HumanoidController(plugin)

    init {
        this.generateGenericReactionMessages()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    lateinit var genericReactionMessages: GenericReactionMessages
    data class GenericReactionMessages(val sleepInterruptionPhrases: MutableList<String>,
                                       val damagePhrases: MutableList<String>,
                                       val joblessPhrases: MutableList<String>,
                                       val noItemsToTradePhrases: MutableList<String>,
                                       val noQuestPhrases: MutableList<String>)

    private fun generateGenericReactionMessages() {
        plugin.bifrost.client.sendRequest(
            prompt = "Your task is to generate generic NPC reaction phrases in ${plugin.controller.setting} setting and put it in JSON with specified keys: " +
                    "‘sleepInterruptionPhrases’ (array of 10 strings; [phrases that NPC says when a player disrupts their sleep]), " +
                    "‘damagePhrases’ (array of 10 strings; [phrases that NPC says when attacked by a player]), " +
                    "‘joblessPhrases’ (array of 10 strings; [phrases that NPC says when a player suggests trading, but NPC doesn't have any job]), " +
                    "‘noItemsToTradePhrases’ (array of 10 strings; [phrases that NPC says when a player suggests trading, but NPC doesn't have any items to trade]), " +
                    "‘noQuestPhrases’ (array of 10 strings; [phrases that NPC says when a player asks NPC about job, but NPC doesn't have any quests for the player]).",
            responseType = GenericReactionMessages::class,
            onSuccess = { genericReactionMessages ->
                this.genericReactionMessages = genericReactionMessages
            },
            onFailure = { error ->
                println("Error during generic reaction messages generation! (${error.message})")
            }
        )
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

    @EventHandler
    private fun onDamage(event: EntityDamageByEntityEvent) {
        (event.damager as? Player)?.let { player ->
            if (event.entity is Villager) movementController.entityProvider.asHumanoid(event.entity as LivingEntity).let { humanoid ->
                player.sendMessage("Gender of this humanoid is ${humanoid.gender}.")
            }
        }
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
        val humanoidRegistry = hashMapOf<LivingEntity, HumanoidController>()
        val HUMANOID_VILLAGERS_ENABLED = plugin.server.pluginManager.isPluginEnabled("Characters")

        fun LivingEntity.getHumanoidController() = humanoidRegistry[this]

        fun Villager.getVoiceSound(): Sound =
            persistentDataContainer.get(HumanoidNamespace.voiceKey, PersistentDataType.STRING)?.let {
                XSound.of(it).get().get()
            } ?: race.let {
                val voices = if (gender == Gender.MALE) it.maleVoices else it.femaleVoices
                voices.random().sound.also { sound -> persistentDataContainer.set(HumanoidNamespace.voiceKey, PersistentDataType.STRING, sound.toString()) }.get() ?: throw NullPointerException("Error during voice sound loading!")
            }

        fun Villager.getVoicePitch() = persistentDataContainer.get(HumanoidNamespace.pitchKey, PersistentDataType.FLOAT) ?: race.let {
            Random.nextDouble(it.maleVoices.random().min, it.maleVoices.random().max).toFloat().also { pitch ->
                persistentDataContainer.set(HumanoidNamespace.pitchKey, PersistentDataType.FLOAT, pitch)
            }
        }

        fun Villager.skin() = race.let { r ->
            persistentDataContainer.get(HumanoidNamespace.skinKey, PersistentDataType.STRING)?.let { skin ->
                val (value, signature) = skin.split(":"); TextureProperty("textures", value, signature)
            } ?: (if (gender == Gender.MALE) r.maleSkins else r.femaleSkins).random().also {
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