package me.voxelsquid.anima.quest

import com.cryptomorin.xseries.XItemStack
import com.cryptomorin.xseries.XSound
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract
import com.github.retrooper.packetevents.event.simple.PacketPlaySendEvent
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent
import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.Ignis.Companion.sendFormattedMessage
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.event.MerchantTradeEvent
import me.voxelsquid.anima.event.PlayerAcceptQuestEvent
import me.voxelsquid.anima.event.QuestInvalidationEvent
import me.voxelsquid.anima.gameplay.settlement.Settlement
import me.voxelsquid.anima.humanoid.HumanoidManager
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.addItemToQuillInventory
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.addQuest
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.isFromSpawner
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.quests
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.removeQuest
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.settlement
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.takeItemFromQuillInventory
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager.Companion.talk
import me.voxelsquid.anima.quest.ProgressTracker.Companion.actualQuests
import me.voxelsquid.anima.quest.ProgressTracker.Companion.experienceEarnedByQuests
import me.voxelsquid.anima.quest.ProgressTracker.Companion.getTrackedQuest
import me.voxelsquid.anima.quest.ProgressTracker.Companion.questTracker
import me.voxelsquid.anima.quest.ProgressTracker.Companion.questsCompleted
import me.voxelsquid.anima.quest.ProgressTracker.Companion.questsFailed
import me.voxelsquid.anima.quest.base.Quest
import me.voxelsquid.anima.quest.base.Quest.QuestData
import me.voxelsquid.anima.quest.gathering.GatheringQuest
import me.voxelsquid.anima.quest.gathering.GatheringQuestType
import me.voxelsquid.anima.quest.gathering.QuestItemPicker
import me.voxelsquid.anima.quest.hunting.HuntingQuest
import me.voxelsquid.anima.settlement.ReputationManager.Companion.Reputation
import me.voxelsquid.anima.settlement.ReputationManager.Companion.addReputation
import me.voxelsquid.anima.settlement.ReputationManager.Companion.getPlayerReputationStatus
import me.voxelsquid.anima.utility.XVanillaPotion
import me.voxelsquid.psyche.HumanoidController.Companion.instance
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.getPersonalityData
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack

class QuestManager : Listener {

    val questItemPicker = QuestItemPicker()
    val progressTracker = ProgressTracker()
    val questItemData   = plugin.controller.databaseManager.getAllQuestItems()

    private val playerQuestLimit           = ConfigurationAccessor(path = "gameplay.quest.player-quest-limit", defaultValue = 3, comments = mutableListOf("The maximum number of quests a player can have at one time.")).get()
    private val questLifetimeDuration      = ConfigurationAccessor(path = "gameplay.core.quest-lifetime-tick", defaultValue = 48000, comments = mutableListOf("Maximum duration of quest existence in ticks.", "When a quest is generated, after the specified number of ticks, it will be deleted and make room for a new one.")).get()
    private val questAcceptedSound         = ConfigurationAccessor(path = "gameplay.quest.accept-sound", defaultValue = Sound.ITEM_BOOK_PAGE_TURN.toString()).get()
    private val reputationPriceMultiplier  = ConfigurationAccessor(path = "reputation.quest.price-multiplier", defaultValue = 0.05, comments = mutableListOf("Reputation multiplier for the price of the item. For example, if an item costs 4000 and the multiplier is 0.05, the player will receive 200 reputation for completing the quest.")).get()
    private val experienceMultiplierPlayer = ConfigurationAccessor(path = "gameplay.quest.experience-multiplier.player", defaultValue = 0.05, comments = mutableListOf("Multiplier of experience points the player will receive when the quest is completed.")).get()
    private val experienceMultiplierNPC    = ConfigurationAccessor(path = "gameplay.quest.experience-multiplier.villager", defaultValue = 0.0025, comments = mutableListOf("Multiplier of experience points the villager who gave quest will receive when the quest is completed.")).get()

    private val cachedQuests = mutableListOf<Quest>()
    private val cachedNames  = mutableListOf<String>()

    // В этом тике мы выбираем жителя и создаём для него квест. Вопрос лишь в том, есть ли необходимость генерировать квест сразу. Думаю, да.
    fun tick() {
        val world = this.selectRandomWorld() ?: run {
            return
        }

        val villager = this.selectRandomVillager(world) ?: run {
            return
        }

        if (villager.getPersonalityData() == null) run {
            return
        }

        if (villager.profession == Villager.Profession.NONE) run {
            return
        }

        if (villager.quests.size > 1 + villager.villagerLevel) run {
            return
        }

        val questType = Quest.Type.entries.random()
        val quest = when (questType) {
            Quest.Type.GATHERING -> GatheringQuest(villager)
            Quest.Type.HUNTING -> {

                // Если рандомнулся охотничий квест, но у жителя нет квеста на развитие профессии, то мы форсим его генерацию.
                if (villager.quests.find { it.gatheringQuestType == GatheringQuestType.PROFESSION_ITEM } == null) {
                    GatheringQuest(villager, gatheringQuestType = GatheringQuestType.PROFESSION_ITEM)
                }

                val scoreLimit = HumanoidManager.InventoryWealth.getProfessionLimit(villager.villagerLevel).maxWealth
                if (questItemData.isEmpty()) {
                    plugin.logger.severe("Quest item database is empty! Use \"/quest item add\" command to add dynamic quest items!")
                    return
                }
                questItemData.filter { it.score <= scoreLimit }.random().let { item ->
                    if (villager.quests.map { it.getRequiredItem() }.find { it.type == XItemStack.deserialize(item.item).type } != null) return
                    HuntingQuest(villager, item.entityType, item)
                }
            }
        }

        cachedQuests.add(quest)
        quest.generate()
    }

    @EventHandler
    fun onMerchantTrade(event: MerchantTradeEvent) {
        event.player.quests.find { it.getRequiredItem().isSimilar(event.recipe.ingredients.first()) }?.let { questData ->

            event.player.closeInventory()

            when (questData.gatheringQuestType) {
                GatheringQuestType.BOOZE -> {
                    this.finishBrewQuest(event.merchant, questData.getRequiredItem()) {
                        plugin.questManager.finishQuest(event.player, event.merchant, questData)
                    }
                }
                GatheringQuestType.FUNGUS_SEARCH -> {
                    this.finishFungusQuest(event.merchant, questData.getRequiredItem()) {
                        plugin.questManager.finishQuest(event.player, event.merchant, questData)
                    }
                }
                else -> plugin.questManager.finishQuest(event.player, event.merchant, questData)
            }

        }
    }

    class ItemGlowHandler : SimplePacketListenerAbstract() {

        init {
            PacketEvents.getAPI().eventManager.registerListener(this)
        }

        private fun Player.channel() : Any = PacketEvents.getAPI().playerManager.getChannel(this)
        private fun Player.sendPacket(packet: PacketWrapper<*>) {
            PacketEvents.getAPI().protocolManager.sendPacket(this.channel(), packet)
        }

        override fun onPacketPlaySend(event: PacketPlaySendEvent) {

            val player = event.getPlayer<Player>() ?: return
            val world = player.world

            // Smart world check.
            if (!plugin.allowedWorlds.contains(world))
                return

            when (event.packetType) {

                // To avoid showing the villagers' real nosy model, we cancel this packet until a packet with a fake entity is sent to the player.
                PacketType.Play.Server.SPAWN_ENTITY -> {

                    val packet = WrapperPlayServerSpawnEntity(event)
                    val entity = SpigotConversionUtil.getEntityById(world, packet.entityId) ?: return

                    when (entity) {

                        // Smart quest tracking.
                        is Item -> {
                            val item = entity.itemStack
                            player.getTrackedQuest()?.let { (quest, progressBar) ->
                                if (quest.getRequiredItem().isSimilar(item) && progressBar.progress != 1.0) {
                                    val glowingData = EntityData(0, EntityDataTypes.BYTE, 0x40.toByte())
                                    val metadataPacket =
                                        WrapperPlayServerEntityMetadata(entity.entityId, listOf(glowingData))
                                    plugin.server.scheduler.runTask(plugin) { _ -> player.sendPacket(metadataPacket) } // Doing things on the next tick is the best fix.
                                }
                            }
                        }
                    }
                }

                else -> { /* Divided by zero! */ }
            }
        }

    }

    class MythicMobHandler : Listener {

        @EventHandler
        fun onMythicMobDeath(event: MythicMobDeathEvent) {
            val killer = (event.killer as? Player) ?: return
            if ((event.entity as LivingEntity).isFromSpawner()) { return }
            killer.quests.find { event.mobType.internalName == it.questItem.entityType }?.let { questData ->
                val dropChance     = questData.questItem.dropChance
                val requiredItem   = questData.getRequiredItem()
                val requiredAmount = requiredItem.amount
                val currentAmount  = killer.inventory.contents.filterNotNull().filter { it.isSimilar(requiredItem) }.sumOf { it.amount }
                if (currentAmount < requiredAmount && dropChance >= Math.random()) {
                    killer.world.dropItemNaturally(event.entity.location, requiredItem.apply { amount = 1; })
                }
            }
        }

    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer
        val type = event.entity.type
        if (event.entity.isFromSpawner()) { return }
        killer?.quests?.find { it.targetEntityType == type.toString() }?.let { questData ->
            val dropChance     = questData.questItem.dropChance
            val requiredItem   = questData.getRequiredItem()
            val requiredAmount = requiredItem.amount
            val currentAmount  = killer.inventory.contents.filterNotNull().filter { it.isSimilar(requiredItem) }.sumOf { it.amount }
            if (currentAmount < requiredAmount && dropChance >= Math.random()) {
                killer.world.dropItemNaturally(event.entity.location, requiredItem.apply { amount = 1; })
            }
        }
    }

    @EventHandler
    fun onPlayerAcceptQuest(event: PlayerAcceptQuestEvent) {

        val player      = event.player
        val villager    = event.villager
        val questData   = event.questData /* Эта QuestData всегда имеет прогресс, равный нулю. */
        val cachedQuest = cachedQuests.find { it.questData.questID == questData.questID } /* Однако, если квест уже кэширован, то нет смысла создавать ещё один экземпляр. */

        if (player.quests.any { it.questID == questData.questID }) {
            player.sendFormattedMessage(plugin.configManager.language.getString("quest.already-accepted")!!)
            return
        }

        if (player.quests.size >= playerQuestLimit) {
            val questLimitMessage = plugin.configManager.language.getString("quest.limit")!!.replace("{playerQuestLimit}", playerQuestLimit.toString())
            player.sendFormattedMessage(questLimitMessage)
            return
        }

        // Определяем класс квеста по типу.
        val quest = when (questData.questType) {
            Quest.Type.GATHERING -> cachedQuest ?: (GatheringQuest(villager, requiredItem = questData.getRequiredItem()) as Quest).apply { this.questData = questData }
            Quest.Type.HUNTING -> HuntingQuest(villager, questData.targetEntityType, questData.questItem).apply { this.questData = questData }
        }

        // Отправляем информацию о новом квесте игроку.
        player.sendFormattedMessage(plugin.configManager.language.getString("quest.accepted")!!.replace("{quest}", questData.questName))
        player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-chat-info.quest-giver")!!.replace("{npcName}", villager.customName!!))
        player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-chat-info.task-description")!!.replace("{desc}", questData.extraShortTaskDescription))
        if (questData.questType == Quest.Type.HUNTING) {
            player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-chat-info.target-entity")!!.replace("{entityType}", questData.targetEntityType))
            player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-chat-info.drop-chance")!!.replace("{chance}", (questData.questItem.dropChance * 100).toString()))
        }
        player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-chat-info.required-item")!!.replace("{itemType}", XItemStack.deserialize(questData.questItem.item).type.toString()))

        // Только один квест может быть активен.
        if (questTracker[player] == null) {
            questTracker[player] = questData to progressTracker.startTracking(player, questData)
        }

        player.addQuest(quest.questData)
        player.playSound(player, XSound.of(questAcceptedSound).get().get() ?: throw NullPointerException(), 1F, 1F)
    }

    @EventHandler
    private fun onQuestInvalidation(event: QuestInvalidationEvent) {

        val player = event.player

        /* Обязательно удаляем активный квест-трэкер, если он есть. */
        progressTracker.stopTracking(player, event.questData)

        val message = when (event.reason) {
            QuestInvalidationEvent.Reason.NPC_DEATH -> plugin.configManager.language.getString("quest.failed.npcDeath")
            QuestInvalidationEvent.Reason.TIME_EXPIRATION -> plugin.configManager.language.getString("quest.failed.timeExpiration")
            QuestInvalidationEvent.Reason.FINISHED_BY_SOMEONE_ELSE -> plugin.configManager.language.getString("quest.failed.finishedBySomeoneElse")
            QuestInvalidationEvent.Reason.NOT_ACTUAL -> plugin.configManager.language.getString("quest.failed.notActual")
        }

        player.sendFormattedMessage(message!!.replace("{quest}", event.questData.questName))
        player.removeQuest(event.questData)
        player.questsFailed += 1
    }

    @EventHandler
    /* Проходимся по всем квестам жителя, когда он умирает. Удаляем их из списка актуальных квестов, и у всех игроков, которые онлайн и имеют этот квест у себя. */
    private fun onVillagerDeath(event: EntityDeathEvent) {
        (event.entity as? Villager)?.let { villager: Villager ->
            villager.quests.forEach { questData ->
                this.invalidateQuest(questData, QuestInvalidationEvent.Reason.NPC_DEATH)
            }
        }
    }

    fun cancelQuest(player: Player, questData: QuestData) {
        progressTracker.stopTracking(player, questData)
        player.removeQuest(questData)
    }

    private fun finishQuest(player: Player, villager: Villager, quest: QuestData, onFinish: () -> Unit = {}) {

        val score = quest.score
        val playerReputation   = (score * reputationPriceMultiplier).toInt()
        val playerExperience   = (score * experienceMultiplierPlayer).toInt()
        val villagerExperience = (score * experienceMultiplierNPC).toInt()

        player.giveExp(playerExperience)
        villager.settlement?.addReputation(player, playerReputation)
        villager.villagerExperience += villagerExperience

        val finishMessage = plugin.configManager.language.getString("quest.finished")!!.replace("{quest}", quest.questName)
        player.sendFormattedMessage(finishMessage)

        progressTracker.stopTracking(player, quest)
        villager.removeQuest(quest)
        player.removeQuest(quest)

        /* Обновляем статистику. */
        player.questsCompleted += 1
        player.experienceEarnedByQuests += playerExperience

        onFinish.invoke()
        this.invalidateQuest(quest, QuestInvalidationEvent.Reason.FINISHED_BY_SOMEONE_ELSE)
        villager.talk(player, this.determineFinishingDialogue(player, villager, quest))
    }

    private fun finishBrewQuest(villager: Villager, potion: ItemStack, reply: () -> Unit) {
        instance.entityProvider.asHumanoid(villager as LivingEntity).consume(villager.world, potion, Sound.ENTITY_GENERIC_DRINK, 5, villager.location, 7) {
            XVanillaPotion.getBasePotionTypeEffect(potion)?.let { effect ->
                villager.addPotionEffect(effect)
            }
            villager.takeItemFromQuillInventory(potion, 1)
            villager.addItemToQuillInventory(ItemStack(Material.GLASS_BOTTLE))
            reply.invoke()
        }
    }

    private fun finishFungusQuest(villager: Villager, fungus: ItemStack, reply: () -> Unit) {
        instance.entityProvider.asHumanoid(villager as LivingEntity).consume(villager.world, ItemStack(Material.SUSPICIOUS_STEW), Sound.ENTITY_GENERIC_EAT, 5, villager.location, 7) {
            villager.takeItemFromQuillInventory(fungus, fungus.amount)
            reply.invoke()
        }
    }

    /* Определяем текст после завершения квеста на основании репутации игрока. */
    private fun determineFinishingDialogue(player: Player, villager: Villager, questData: QuestData) : String {
        return (villager.settlement?.let { settlement: Settlement ->
            return@let when (player.getPlayerReputationStatus(settlement)) {
                Reputation.EXALTED -> questData.reputationBasedQuestFinishingDialogues[7]
                Reputation.REVERED -> questData.reputationBasedQuestFinishingDialogues[6]
                Reputation.HONORED -> questData.reputationBasedQuestFinishingDialogues[5]
                Reputation.FRIENDLY -> questData.reputationBasedQuestFinishingDialogues[4]
                Reputation.NEUTRAL -> questData.reputationBasedQuestFinishingDialogues[3]
                Reputation.UNFRIENDLY -> questData.reputationBasedQuestFinishingDialogues[2]
                Reputation.HOSTILE -> questData.reputationBasedQuestFinishingDialogues[1]
                Reputation.EXILED -> questData.reputationBasedQuestFinishingDialogues[0]
            }
        } ?: questData.reputationBasedQuestFinishingDialogues[4]).replace("%playerName%", player.name)
    }

    /* Общий метод инвалидации квеста. Указанный квест будет удалён из списка актуальных квестов, и у всех игроков онлайн. */
    fun invalidateQuest(questData: QuestData, reason: QuestInvalidationEvent.Reason) {
        actualQuests.remove(questData.questID)
        Bukkit.getOnlinePlayers().forEach { onlinePlayer ->
            onlinePlayer.quests.find { it.questID == questData.questID }?.let {
                HumanoidManager.plugin.server.pluginManager.callEvent(QuestInvalidationEvent(onlinePlayer, it, reason))
            }
        }
    }

    // Выбираем случайного жителя, предварительно отчистив старые квесты.
    private fun selectRandomVillager(world: World) : Villager? {
        return world.entities.filterIsInstance<Villager>().onEach { villager: Villager ->
            villager.quests.forEach { quest ->
                if ((System.currentTimeMillis() - quest.timeCreated) / 1000 * 20 > questLifetimeDuration) {
                    this.invalidateQuest(quest, QuestInvalidationEvent.Reason.TIME_EXPIRATION)
                    villager.removeQuest(quest)
                }
            }
        }.filter { it.quests.size < it.villagerLevel + 1 }.randomOrNull()
    }

    // Находим мир, где есть жители. Фильтрация позволит пропускать итерации, в которых ничего не происходит.
    private fun selectRandomWorld() : World? {
        return plugin.allowedWorlds.filter { it.entities.filterIsInstance<Villager>().isNotEmpty() }.randomOrNull()
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        if (plugin.server.pluginManager.isPluginEnabled("MythicMobs")) {
            plugin.server.pluginManager.registerEvents(MythicMobHandler(), plugin)
        }
        ItemGlowHandler()
    }

    companion object {
        val plugin = ignisInstance
    }

}