package me.voxelsquid.anima.command

import co.aikar.commands.BaseCommand
import co.aikar.commands.BukkitCommandManager
import co.aikar.commands.annotation.*
import io.lumine.mythic.bukkit.MythicBukkit
import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.Ignis.Companion.sendFormattedMessage
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.gameplay.settlement.Settlement
import me.voxelsquid.anima.humanoid.HumanoidManager
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.quests
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager.Companion.DialogueFormat
import me.voxelsquid.anima.settlement.ReputationManager
import me.voxelsquid.anima.settlement.ReputationManager.Companion.setReputation
import me.voxelsquid.anima.settlement.SettlementManager
import me.voxelsquid.anima.settlement.SettlementManager.Companion.settlements
import me.voxelsquid.psyche.data.PersonalityType
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.setPersonalityType
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.command.CommandSender
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.persistence.PersistentDataType
import java.lang.IllegalArgumentException

@CommandAlias("ignis")
class CommandController : BaseCommand() {

    init {
        val commandManager = BukkitCommandManager(plugin)
        commandManager.registerCommand(this)
        commandManager.registerCommand(QuestCommand())
        commandManager.commandCompletions.registerCompletion("acceptedQuests") { context ->
            context.player.quests.map { it.questName }
        }
        commandManager.commandCompletions.registerCompletion("villagerTypes") {
            listOf("SNOW", "JUNGLE", "DESERT", "SAVANNA", "TAIGA", "SWAMP", "PLAINS")
        }
        commandManager.commandCompletions.registerCompletion("entityTypes") {
            EntityType.entries.map { it.toString() }.toMutableList().apply {
                if (plugin.server.pluginManager.isPluginEnabled("MythicMobs")) {
                    this.addAll( MythicBukkit.inst().mobManager.mobTypes.map { it.internalName })
                }
            }
        }
        commandManager.commandCompletions.registerCompletion("reputationStates") {
            ReputationManager.Companion.Reputation.entries.map { it.name }
        }
        commandManager.commandCompletions.registerCompletion("villagerPersonalities") {
            PersonalityType.entries.map { it.name }
        }
        commandManager.commandCompletions.registerCompletion("settlements") { context ->
            settlements[context.player.world]?.map { it.data.settlementName }
        }

    }

    // TODO: Help command.

    @Subcommand("dialogue format")
    @CommandPermission("ignis.dialogue.format")
    @Description("Specialized debug command for easy testing of villagers.")
    fun onDialogueFormat(player: Player, format: DialogueFormat) {
        if (DialogueFormat.entries.find { type -> type == format } != null) {
            player.persistentDataContainer.set(immersiveDialoguesKey, PersistentDataType.STRING, format.toString())
            plugin.configManager.language.let { language ->
                language.getString("command-message.dialogue-format-changed")?.let { message ->
                    player.sendFormattedMessage(message.replace("{dialogueFormat}", format.toString()))
                }
            }
        }
    }

    @Subcommand("villager create")
    @CommandPermission("ignis.villager.create")
    @CommandCompletion("@villagerPersonalities @villagerTypes")
    @Description("Specialized debug command for easy testing of villagers.")
    fun onVillager(player: Player, characterType: PersonalityType, type: String) {

        val world = player.world
        val villager = world.spawnEntity(player.location, EntityType.VILLAGER) as Villager

        villager.setPersonalityType(characterType)

        villager.villagerType = when (type) {
            "SNOW" -> Villager.Type.SNOW
            "JUNGLE" -> Villager.Type.JUNGLE
            "DESERT" -> Villager.Type.DESERT
            "SAVANNA" -> Villager.Type.SAVANNA
            "TAIGA" -> Villager.Type.TAIGA
            "SWAMP" -> Villager.Type.SWAMP
            else -> Villager.Type.PLAINS
        }

    }

    @Subcommand("settlement list")
    @CommandPermission("ignis.settlement.list")
    fun onSettlementList(player: Player) {
        player.sendMessage("§6[-] §7Settlements:")
        settlements[player.world]?.forEach { settlement ->
            player.sendMessage(" §7- §6${settlement.data.settlementName}")
        }
    }

    @Subcommand("settlement teleport")
    @CommandPermission("ignis.settlement.teleport")
    @CommandCompletion("@settlements")
    fun onSettlementTeleport(player: Player, settlementName: String) {
        val settlement = settlements[player.world]?.find { it.data.settlementName == settlementName }
        if (settlement == null) {
            player.sendMessage("§4Settlement $settlementName not found.")
            return
        }
        player.teleport(settlement.data.center)
    }

    @Subcommand("settlement reputation")
    @CommandPermission("ignis.settlement.reputation")
    @CommandCompletion("@players @reputationStates @settlements")
    fun onSettlementReputation(sender: CommandSender, target: String, status: String, settlementName: String) {

        val prefix = plugin.controller.messagePrefix
        val playerNotFoundMessage = ConfigurationAccessor(fileName = "language.yml", path = "command-error-message.player-not-found", defaultValue = "§cError! Player not found: §b{playerName} §c(online players only).").get()
        val statusNotFoundMessage = ConfigurationAccessor(fileName = "language.yml", path = "command-error-message.status-not-found", defaultValue = "§cError! Non-existent reputation status: §с{status}.").get()
        val nonExistingSettlement = ConfigurationAccessor(fileName = "language.yml", path = "command-error-message.settlement-not-found", defaultValue = "§cError! Non-existent settlement status: §с{settlementName}.").get()
        val commandSuccessMessage = ConfigurationAccessor(fileName = "language.yml", path = "command-success-message.reputation-changed", defaultValue = "§7Reputation of §e{playerName} §7has been changed to §6{status}§7.").get()

        val settlement = SettlementManager.getByName(settlementName)
        if (settlement == null) {
            sender.sendMessage(prefix + nonExistingSettlement.replace("{settlementName}", settlementName))
            return
        }

        val reputation = try {
            ReputationManager.Companion.Reputation.valueOf(status)
        } catch (exception: IllegalArgumentException) {
            sender.sendMessage(prefix + statusNotFoundMessage.replace("{status}", status))
            return
        }

        Bukkit.getPlayer(target)?.let { targetPlayer ->
            SettlementManager.getByName(settlementName)?.let { settlement: Settlement ->
                settlement.setReputation(targetPlayer, reputation.requiredReputationAmount)
                sender.sendMessage(prefix + commandSuccessMessage.replace("{playerName}", target).replace("{status}", reputation.localizedName.get()))
            }
        } ?: sender.sendMessage(prefix + playerNotFoundMessage.replace("{playerName}", target))

    }

    companion object {
        private val plugin = ignisInstance
        private val immersiveDialoguesKey = NamespacedKey(plugin, "ImmersiveDialogues")
    }

}