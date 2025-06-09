package me.voxelsquid.anima.utility

import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.event.PlayerAcceptQuestEvent
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.quests
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.settlement
import me.voxelsquid.anima.humanoid.HumanoidTradeHandler.Companion.openTradeMenu
import me.voxelsquid.anima.humanoid.dialogue.DialogueSession
import me.voxelsquid.anima.humanoid.dialogue.DialogueSession.Companion.getActiveDialogueSession
import me.voxelsquid.anima.humanoid.dialogue.menu.InteractionManager
import me.voxelsquid.anima.humanoid.dialogue.menu.InteractionManager.Companion
import me.voxelsquid.anima.settlement.ReputationManager.Companion.Reputation
import me.voxelsquid.anima.settlement.ReputationManager.Companion.getPlayerReputationStatus
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.geysermc.cumulus.form.Form
import org.geysermc.cumulus.form.ModalForm
import org.geysermc.cumulus.form.SimpleForm
import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.geyser.api.GeyserApi

class GeyserSupportProvider {

    companion object {
        val plugin = ignisInstance
        val dialogueBoxTextBaseColor        = ConfigurationAccessor("text-formatting.dialogue-box.text.base-color", "&f", mutableListOf("Standard color of common words in dialogue boxes.")).get()
        val dialogueBoxTextImportantColor   = ConfigurationAccessor("text-formatting.dialogue-box.text.important-color", "&5", mutableListOf("Color of important words in dialogue boxes that the AI will try to pay attention to.")).get()
        val dialogueBoxTextInterestingColor = ConfigurationAccessor("text-formatting.dialogue-box.text.interesting-color", "&6", mutableListOf("Color of interesting words in dialogue boxes that may be interesting to the player.")).get()
    }

    init {
        plugin.logger.info("Geyser usage detected. Support for Bedrock Edition players will be provided.")
    }

    fun checkGeyserPlayer(player: Player) : Boolean = try {
        GeyserApi.api().connectionByUuid(player.uniqueId) != null
    } catch (exception: Exception) {
        false
    }

    private fun openForm(player: Player, form: Form) {
        if (plugin.server.pluginManager.isPluginEnabled("floodgate")) {
            FloodgateApi.getInstance().sendForm(player.uniqueId, form)
        } else {
            GeyserApi.api().sendForm(player.uniqueId, form)
        }
    }

    // QI automatically detects if the player is playing through Geyser, and if true, selects a menu from Form. Suddenly, Bedrock Edition has one cool feature — the ability to create your own GUI.
    fun openInteractionMenu(player: Player, villager: Villager) {

        val questListForm = SimpleForm.builder()
            .title(plugin.configManager.language.getString("interaction-menu.quests-button")!!)

        villager.quests.forEach { quest ->
            questListForm.button(quest.questName)
        }

        questListForm.button(plugin.configManager.language.getString("interaction-menu.close-button")!!)
        questListForm.validResultHandler { response ->

            val buttonName = response.clickedButton().text()
            if (buttonName == plugin.configManager.language.getString("interaction-menu.close-button")!!) return@validResultHandler

            val quest = villager.quests.find { it.questName == response.clickedButton().text() } ?: return@validResultHandler

            // Looking for a quest description.
            val questDescription = quest.let {
                (villager.settlement?.let { settlement ->
                    when (player.getPlayerReputationStatus(settlement)) {
                        Reputation.EXALTED -> quest.reputationBasedQuestDescriptions[7]
                        Reputation.REVERED -> quest.reputationBasedQuestDescriptions[6]
                        Reputation.HONORED -> quest.reputationBasedQuestDescriptions[5]
                        Reputation.FRIENDLY -> quest.reputationBasedQuestDescriptions[4]
                        Reputation.NEUTRAL -> quest.reputationBasedQuestDescriptions[3]
                        Reputation.UNFRIENDLY -> quest.reputationBasedQuestDescriptions[2]
                        Reputation.HOSTILE -> quest.reputationBasedQuestDescriptions[1]
                        Reputation.EXILED -> quest.reputationBasedQuestDescriptions[0]
                    }
                } ?: quest.reputationBasedQuestDescriptions[3]).replace("%playerName%", player.name)
            }

            // Markdown parsing.
            val formattedQuestDescription = dialogueBoxTextBaseColor + questDescription.replace(Regex("\\*\\*(.*?)\\*\\*")) { matchResult ->
                "${dialogueBoxTextImportantColor}${matchResult.groupValues[1]}${dialogueBoxTextBaseColor}"
            }.replace(Regex("\\*(.*?)\\*")) { matchResult ->
                "${dialogueBoxTextInterestingColor}${matchResult.groupValues[1]}${dialogueBoxTextBaseColor}"
            }.replace("\\\"", "\"")

            // Menu with quest description.
            val questDescriptionForm = ModalForm.builder()
                .title(response.clickedButton().text())
                .content(formattedQuestDescription)
                .button1(plugin.configManager.language.getString("interaction-menu.accept-button")!!)
                .button2(plugin.configManager.language.getString("interaction-menu.close-button")!!)
                .validResultHandler { responseData ->
                    if (responseData.clickedButtonText() == plugin.configManager.language.getString("interaction-menu.accept-button")!!) {
                        plugin.server.scheduler.runTask(plugin) { _ ->
                            plugin.server.pluginManager.callEvent(PlayerAcceptQuestEvent(player, villager, quest))
                        }
                    }
                }

            this.openForm(player, questDescriptionForm.build())
        }

        val dialogueSessionForm = SimpleForm.builder()
            .title(villager.customName ?: "Villager")
            .button(plugin.configManager.language.getString("interaction-menu.quests-button")!!)
            .button(plugin.configManager.language.getString("interaction-menu.trade-button")!!)
            .button(plugin.configManager.language.getString("interaction-menu.gift-button")!!)
            .button(plugin.configManager.language.getString("interaction-menu.interrupt-button")!!)
            .button(plugin.configManager.language.getString("interaction-menu.close-button")!!)
            .validResultHandler { responseData ->
                if (responseData.clickedButton().text() == plugin.configManager.language.getString("interaction-menu.quests-button")!!) {
                    this.openForm(player, questListForm.build())
                }
                if (responseData.clickedButton().text() == plugin.configManager.language.getString("interaction-menu.trade-button")!!) {
                    plugin.server.scheduler.runTask(plugin) { _ ->
                        villager.openTradeMenu(player)
                    }
                }
                if (responseData.clickedButton().text() == plugin.configManager.language.getString("interaction-menu.gift-button")!!) {
                    if (player.getActiveDialogueSession()?.giftAwaiting == false) player.getActiveDialogueSession()?.giftAwaiting = true
                }
                if (responseData.clickedButton().text() == plugin.configManager.language.getString("interaction-menu.interrupt-button")!!) {
                    player.getActiveDialogueSession()?.cancelled = true
                }
            }

        val mainForm = SimpleForm.builder()
            .title(villager.customName ?: "Villager")
            .button(plugin.configManager.language.getString("interaction-menu.quests-button")!!)
            .button(plugin.configManager.language.getString("interaction-menu.trade-button")!!)
            .button(plugin.configManager.language.getString("interaction-menu.talk-button")!!)
            .button(plugin.configManager.language.getString("interaction-menu.close-button")!!)
            .validResultHandler { responseData ->
                if (responseData.clickedButton().text() == plugin.configManager.language.getString("interaction-menu.quests-button")!!) {
                    this.openForm(player, questListForm.build())
                }
                if (responseData.clickedButton().text() == plugin.configManager.language.getString("interaction-menu.trade-button")!!) {
                    plugin.server.scheduler.runTask(plugin) { _ ->
                        villager.openTradeMenu(player)
                    }
                }
                if (responseData.clickedButton().text() == plugin.configManager.language.getString("interaction-menu.talk-button")!!) {
                    if (player.getActiveDialogueSession() == null) DialogueSession(player, villager)
                }
            }

        this.openForm(player, if(player.getActiveDialogueSession() != null) dialogueSessionForm.build() else mainForm.build())
    }

}