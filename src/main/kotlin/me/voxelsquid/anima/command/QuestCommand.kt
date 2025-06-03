package me.voxelsquid.anima.command

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import com.cryptomorin.xseries.XItemStack
import io.lumine.mythic.bukkit.MythicBukkit
import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.Ignis.Companion.sendFormattedMessage
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.quests
import me.voxelsquid.anima.quest.ProgressTracker.Companion.experienceEarnedByQuests
import me.voxelsquid.anima.quest.ProgressTracker.Companion.getTrackedQuest
import me.voxelsquid.anima.quest.ProgressTracker.Companion.questsCompleted
import me.voxelsquid.anima.quest.ProgressTracker.Companion.questsFailed
import me.voxelsquid.anima.quest.base.Quest.Companion.questCount
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player

@CommandAlias("quest|q")
class QuestCommand : BaseCommand() {

    @Subcommand("remove")
    @CommandPermission("ignis.quest.remove")
    @CommandCompletion("@acceptedQuests")
    fun onQuestRemove(player: Player, questName: String) {
        val questData = player.quests.find { it.questName == questName } ?: run {
            val questNotFoundMessage = plugin.configManager.language.getString("info-messages.quest-command.not-found")!!.replace("{quest}", questName)
            player.sendFormattedMessage(questNotFoundMessage)
            return
        }
        plugin.questManager.cancelQuest(player, questData)
        val questRemovedMessage = plugin.configManager.language.getString("info-messages.quest-command.removed")!!.replace("{quest}", questName)
        player.sendFormattedMessage(questRemovedMessage)
    }

    @Subcommand("track")
    @CommandPermission("ignis.quest.track")
    @CommandCompletion("@acceptedQuests")
    fun onQuestTrack(player: Player, questName: String) {
        val questData = player.quests.find { it.questName == questName } ?: run {
            val questNotFoundMessage = plugin.configManager.language.getString("info-messages.quest-command.not-found")!!.replace("{quest}", questName)
            player.sendFormattedMessage(questNotFoundMessage)
            return
        }
        player.getTrackedQuest()?.let {
            plugin.questManager.progressTracker.stopTracking(player, it.first)
        }
        plugin.questManager.progressTracker.startTracking(player, questData)
        val questTrackingMessage = plugin.configManager.language.getString("info-messages.quest-command.tracking")!!.replace("{quest}", questName)
        player.sendFormattedMessage(questTrackingMessage)
    }

    @Subcommand("list")
    @CommandPermission("ignis.quest.list")
    fun onQuestList(player: Player) {
        val quests = player.quests
        val questAmountMessage = plugin.configManager.language.getString("info-messages.quest-command.amount")!!.replace("{questAmount}", quests.size.toString())
        player.sendFormattedMessage(questAmountMessage)
        quests.forEach { quest ->
            player.sendMessage(" §7- §6${quest.questName}")
        }
    }

    @Subcommand("stats")
    @CommandPermission("ignis.quest.stats")
    fun onStats(player: Player) {
        val questStatsMessage = plugin.configManager.language.getString("info-messages.quest-command.statistics")!!
        val tgqMessage = plugin.configManager.language.getString("info-messages.quest-command.totally-generated")!!.replace("{questCount}", questCount.toString())
        val dqiMessage = plugin.configManager.language.getString("info-messages.quest-command.dqi-amount")!!.replace("{dqiAmount}", plugin.questManager.questItemData.size.toString())
        val completedAmountMessage = plugin.configManager.language.getString("info-messages.quest-command.completed")!!.replace("{questsCompleted}", player.questsCompleted.toString())
        val failedAmountMessage = plugin.configManager.language.getString("info-messages.quest-command.failed")!!.replace("{questsFailed}", player.questsFailed.toString())
        val xpEarnedMessage = plugin.configManager.language.getString("info-messages.quest-command.xp-earned")!!.replace("{xpEarned}", player.experienceEarnedByQuests.toString())
        player.sendFormattedMessage(questStatsMessage)
        player.sendFormattedMessage(tgqMessage)
        player.sendFormattedMessage(dqiMessage)
        player.sendFormattedMessage(completedAmountMessage)
        player.sendFormattedMessage(failedAmountMessage)
        player.sendFormattedMessage(xpEarnedMessage)
    }

    @Subcommand("item add")
    @CommandPermission("ignis.quest.item.add")
    @CommandCompletion("@entityTypes <score>-(int-2000) <quantity>-(int-1) <dropChance>-(double:0.0-1.0)")
    fun onItemAdd(player: Player, entityType: String, score: Int, quantity: Int, dropChance: Double) {
        if (plugin.isFree(player)) return
        val item = player.inventory.itemInMainHand; if (item.type == Material.AIR) run { player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-command.hold-item")!!); return }
        val type = try { EntityType.valueOf(entityType).toString() } catch (exception: Exception) {
            MythicBukkit.inst().mobManager.mobTypes.find { it.internalName == entityType }?.let { mythicMob ->
                player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-command.mm-dqi-added")!!.replace("{entityType}", entityType))
                mythicMob.internalName
            } ?: run { player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-command.ee-not-exist")!!.replace("{entityType}", entityType)); return }
        }
        plugin.controller.databaseManager.saveQuestItem(type, item, quantity, dropChance, score)
        player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-command.dqi-added")!!.replace("{entityType}", entityType))
    }

    @Subcommand("item list")
    @CommandPermission("ignis.quest.item.list")
    @CommandCompletion("@entityTypes")
    fun onItemList(player: Player, entityType: String) {
        if (plugin.isFree(player)) return
        player.sendFormattedMessage("Dynamic Quest Items, $entityType:")
        plugin.questManager.questItemData.filter { it.entityType == entityType }.forEach { questItem ->
            val id         = questItem.id
            val name       = XItemStack.deserialize(questItem.item).itemMeta?.displayName
            val quantity   = questItem.quantity
            val score      = questItem.score
            val dropChance = questItem.dropChance
            player.sendFormattedMessage("§a$id §8| $name §8| §e$quantity §8| §e$score$ §8| §e$dropChance%")
        }
        player.sendFormattedMessage("————————————————————————————")
    }

    @Subcommand("item clear")
    @CommandPermission("ignis.quest.item.clear")
    @CommandCompletion("@entityTypes")
    fun onItemClear(player: Player, entityType: String) {
        if (plugin.isFree(player)) return
        val rowsAffected = plugin.controller.databaseManager.deleteQuestItem(entityType)
        plugin.questManager.questItemData.removeIf { it.entityType == entityType }
        player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-command.deleted-dqi-by-ee")!!.replace("{rowsAffected}", rowsAffected.toString()).replace("{entityType}", entityType))
    }

    @Subcommand("item remove")
    @CommandPermission("ignis.quest.item.remove")
    @CommandCompletion("<score>-(int-0)")
    fun onItemRemove(player: Player, index: Int) {
        if (plugin.isFree(player)) return
        val result = plugin.controller.databaseManager.deleteQuestItem(index)
        plugin.questManager.questItemData.removeIf { it.id == index }
        if (result) {
            player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-command.deleted-dqi-by-id")!!.replace("{id}", index.toString()))
        } else player.sendFormattedMessage(plugin.configManager.language.getString("info-messages.quest-command.non-existent-id")!!.replace("{id}", index.toString()))
    }

    companion object {
        val plugin = ignisInstance
    }

}